--liquibase formatted sql

-- ============================================================================
-- Ядро: пользователи и права, аудит, ошибки, версионируемые параметры,
-- единое файловое хранилище и карта legacy-идентификаторов.
-- ============================================================================

--changeset dipol:001-users
CREATE TABLE users (
    id              BIGSERIAL PRIMARY KEY,
    username        TEXT        NOT NULL UNIQUE,
    password_hash   TEXT        NOT NULL,
    display_name    TEXT        NOT NULL,
    -- Стартовые профили доступа из US-N1. Заказчик доступа в ERP не получает.
    role            TEXT        NOT NULL CHECK (role IN (
                        'SUPERVISOR',   -- руководитель/собственник: всё
                        'ADMIN',         -- администратор системы: настройки, справочники, права
                        'OPERATOR',      -- оператор: клиенты, заявки, документы
                        'FOREMAN',       -- прораб/мастер: планирование, приёмка факта
                        'WORKER',        -- рабочий: свои задачи, мобильный кабинет
                        'SAFETY',        -- техник по безопасности: раздел ТБ
                        'READONLY')),
    employee_id     BIGINT,             -- FK проставляется в 002 после employees
    is_active       BOOLEAN     NOT NULL DEFAULT TRUE,
    pin_code        TEXT,               -- вход рабочего в мобильный кабинет
    created_at      TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP   NOT NULL DEFAULT NOW()
);

-- Точечные права поверх ролей: снабжение и расчёты выдаются без отдельной роли (US-N1).
CREATE TABLE permissions (
    code        TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    section     TEXT NOT NULL
);

CREATE TABLE role_permissions (
    role            TEXT NOT NULL,
    permission_code TEXT NOT NULL REFERENCES permissions(code),
    PRIMARY KEY (role, permission_code)
);

CREATE TABLE user_permissions (
    user_id         BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    permission_code TEXT   NOT NULL REFERENCES permissions(code),
    granted         BOOLEAN NOT NULL DEFAULT TRUE,   -- FALSE — явный отзыв права роли
    PRIMARY KEY (user_id, permission_code)
);

--changeset dipol:001-audit
-- Журнал действий (US-N2). Запись не редактируется пользователем: UPDATE/DELETE
-- на эту таблицу в коде отсутствуют, правки выполняются новыми записями.
CREATE TABLE audit_log (
    id          BIGSERIAL PRIMARY KEY,
    entity_type TEXT        NOT NULL,
    entity_id   BIGINT,
    action      TEXT        NOT NULL,      -- CREATE | UPDATE | DELETE | STATUS | REVERSE | OVERRIDE
    field       TEXT,
    old_value   TEXT,
    new_value   TEXT,
    comment     TEXT,
    actor       TEXT,
    created_at  TIMESTAMP   NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_audit_entity ON audit_log(entity_type, entity_id);
CREATE INDEX idx_audit_created ON audit_log(created_at DESC);

CREATE TABLE error_log (
    id           BIGSERIAL PRIMARY KEY,
    error_type   TEXT      NOT NULL,
    message      TEXT      NOT NULL,
    request_path TEXT,
    actor        TEXT,
    created_at   TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_error_created ON error_log(created_at DESC);

--changeset dipol:001-parameters
-- Версионируемые нормативы вместо magic numbers (US-N6, Q-02): 150 м²/подъезд,
-- 15 м, лимит локремонта 10 м², порог жалоб, лимит белой ЗП и т.п.
CREATE TABLE parameters (
    id          BIGSERIAL PRIMARY KEY,
    code        TEXT NOT NULL UNIQUE,
    name        TEXT NOT NULL,
    unit_code   TEXT,
    value_type  TEXT NOT NULL DEFAULT 'NUMBER' CHECK (value_type IN ('NUMBER','TEXT','BOOL')),
    description TEXT
);

CREATE TABLE parameter_versions (
    id            BIGSERIAL PRIMARY KEY,
    parameter_id  BIGINT      NOT NULL REFERENCES parameters(id),
    value_num     NUMERIC(18,4),
    value_text    TEXT,
    valid_from    DATE        NOT NULL,
    valid_to      DATE,
    author        TEXT,
    comment       TEXT,
    created_at    TIMESTAMP   NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_param_version ON parameter_versions(parameter_id, valid_from DESC);

--changeset dipol:001-files
-- Единое внутреннее файловое хранилище (US-K4). Физически файл лежит один раз,
-- в ERP он виден из всех связанных карточек через file_links.
CREATE TABLE files (
    id            BIGSERIAL PRIMARY KEY,
    storage_key   TEXT        NOT NULL,          -- путь внутри APP_FILES_DIR
    original_name TEXT        NOT NULL,
    content_type  TEXT,
    size_bytes    BIGINT,
    doc_kind      TEXT,                          -- ACT | CONTRACT | PHOTO | PERMIT | INVOICE | OTHER
    version       INT         NOT NULL DEFAULT 1,
    replaces_id   BIGINT REFERENCES files(id),
    external_url  TEXT,                          -- ссылка на внешнее хранилище (Диск, MAX)
    is_restricted BOOLEAN     NOT NULL DEFAULT FALSE,  -- финансовые/служебные — скрыты от рабочих
    uploaded_by   TEXT,
    created_at    TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE TABLE file_links (
    id          BIGSERIAL PRIMARY KEY,
    file_id     BIGINT NOT NULL REFERENCES files(id) ON DELETE CASCADE,
    entity_type TEXT   NOT NULL,   -- CLIENT | PROJECT | BUILDING | REQUEST | TRIP | VEHICLE | SAFETY | ...
    entity_id   BIGINT NOT NULL,
    slot        TEXT,              -- «Договоры», «Фото до», «Полис ОСАГО» …
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (file_id, entity_type, entity_id, slot)
);
CREATE INDEX idx_file_links_entity ON file_links(entity_type, entity_id);

--changeset dipol:001-legacy-map
-- Соответствие «строка исходной таблицы → сущность ERP» (US-N5). Нужно и для
-- повторного импорта без дублей, и для протокола контрольной сверки.
CREATE TABLE legacy_map (
    id           BIGSERIAL PRIMARY KEY,
    entity_type  TEXT      NOT NULL,     -- BUILDING | REQUEST | PAYROLL | MATERIAL_MOVE | ...
    entity_id    BIGINT    NOT NULL,
    source_book  TEXT      NOT NULL,     -- Заявки_Лок.Рем. | Материалы_ФОТ | МКД | ...
    source_sheet TEXT      NOT NULL,
    source_row   INT,
    source_key   TEXT      NOT NULL,     -- «№ заявки в смете», ID_Дома и т.п.
    imported_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (source_book, source_sheet, source_key)
);
CREATE INDEX idx_legacy_entity ON legacy_map(entity_type, entity_id);

-- Протокол сверки импорта: сколько строк/сумм было в источнике и сколько в ERP.
CREATE TABLE import_reconciliation (
    id            BIGSERIAL PRIMARY KEY,
    source_book   TEXT      NOT NULL,
    source_sheet  TEXT      NOT NULL,
    period        TEXT,
    metric        TEXT      NOT NULL,    -- ROWS | SUM_AMOUNT | SUM_VOLUME
    source_value  NUMERIC(18,4),
    erp_value     NUMERIC(18,4),
    diff          NUMERIC(18,4),
    note          TEXT,
    created_at    TIMESTAMP NOT NULL DEFAULT NOW()
);

--changeset dipol:001-data-quality
-- Очередь ошибок и предупреждений качества данных (US-M4).
CREATE TABLE data_issues (
    id            BIGSERIAL PRIMARY KEY,
    issue_type    TEXT      NOT NULL,   -- ADDRESS_UNMATCHED | COST_UNALLOCATED | DOC_MISSING | ...
    severity      TEXT      NOT NULL DEFAULT 'WARNING' CHECK (severity IN ('BLOCKING','WARNING')),
    entity_type   TEXT,
    entity_id     BIGINT,
    message       TEXT      NOT NULL,
    responsible   TEXT,
    resolved_at   TIMESTAMP,
    resolved_by   TEXT,
    created_at    TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_data_issues_open ON data_issues(issue_type) WHERE resolved_at IS NULL;

--changeset dipol:001-notifications
CREATE TABLE notifications (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT REFERENCES users(id) ON DELETE CASCADE,
    title       TEXT      NOT NULL,
    body        TEXT,
    link        TEXT,
    is_read     BOOLEAN   NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_notifications_user ON notifications(user_id, is_read);
