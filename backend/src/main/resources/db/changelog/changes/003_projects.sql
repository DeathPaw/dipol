--liquibase formatted sql

-- ============================================================================
-- Двухуровневая модель проекта (US-A3, US-O3..O5):
--   project_configs — переиспользуемый версионируемый набор правил направления;
--   projects        — конкретный длительный или разовый проект у клиента.
-- Правила живут в данных, а не в коде: набор SKU, статусная схема, обязательные
-- поля, чек-лист закрытия и допустимые схемы ФОТ настраиваются из интерфейса.
-- ============================================================================

--changeset dipol:003-request-statuses
-- Базовая статусная схема заявки (US-B5). «Оплачена» базовым статусом не является.
CREATE TABLE request_statuses (
    code        TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    sort_order  INT  NOT NULL,
    is_terminal BOOLEAN NOT NULL DEFAULT FALSE,
    color       TEXT
);

--changeset dipol:003-configs
CREATE TABLE project_configs (
    id                BIGSERIAL PRIMARY KEY,
    code              TEXT    NOT NULL UNIQUE,
    name              TEXT    NOT NULL,
    description       TEXT,
    -- Включает ли конфигурация промежуточный уровень «объект работ»
    -- (крыша, фасад, козырёк, подъезд). Если нет — схема «заявка → SKU».
    use_work_objects  BOOLEAN NOT NULL DEFAULT FALSE,
    default_lifecycle TEXT    NOT NULL DEFAULT 'PERMANENT'
                      CHECK (default_lifecycle IN ('PERMANENT','ONE_OFF')),
    -- Допустимые схемы ФОТ: для АБ основного и АБ-доп обязательны SHIFT и PIECE_KPI.
    allowed_pay_schemes TEXT  NOT NULL DEFAULT 'SHIFT,PIECE_KPI',
    materials_mode    TEXT    NOT NULL DEFAULT 'FACT'
                      CHECK (materials_mode IN ('FACT','PLAN_FACT')),
    current_version   INT     NOT NULL DEFAULT 1,
    is_active         BOOLEAN NOT NULL DEFAULT TRUE,
    created_by        TEXT,
    created_at        TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Версия правил. Закрытые заявки и начисления ссылаются на свою версию и
-- не пересчитываются задним числом при изменении конфигурации.
CREATE TABLE project_config_versions (
    id         BIGSERIAL PRIMARY KEY,
    config_id  BIGINT  NOT NULL REFERENCES project_configs(id) ON DELETE CASCADE,
    version    INT     NOT NULL,
    rules_json TEXT,                 -- снимок правил на момент выпуска версии
    valid_from DATE    NOT NULL DEFAULT CURRENT_DATE,
    author     TEXT,
    comment    TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (config_id, version)
);

CREATE TABLE project_config_skus (
    config_id  BIGINT NOT NULL REFERENCES project_configs(id) ON DELETE CASCADE,
    sku_id     BIGINT NOT NULL REFERENCES sku(id),
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order INT NOT NULL DEFAULT 100,
    PRIMARY KEY (config_id, sku_id)
);

CREATE TABLE project_config_statuses (
    config_id   BIGINT NOT NULL REFERENCES project_configs(id) ON DELETE CASCADE,
    status_code TEXT   NOT NULL REFERENCES request_statuses(code),
    sort_order  INT    NOT NULL DEFAULT 100,
    PRIMARY KEY (config_id, status_code)
);

CREATE TABLE project_config_transitions (
    config_id   BIGINT NOT NULL REFERENCES project_configs(id) ON DELETE CASCADE,
    from_status TEXT   NOT NULL REFERENCES request_statuses(code),
    to_status   TEXT   NOT NULL REFERENCES request_statuses(code),
    PRIMARY KEY (config_id, from_status, to_status)
);

-- Обязательность полей заявки задаётся конфигурацией, а не кодом.
CREATE TABLE project_config_fields (
    config_id   BIGINT NOT NULL REFERENCES project_configs(id) ON DELETE CASCADE,
    field_code  TEXT   NOT NULL,   -- apartment | entrance | external_number | urgency | ...
    is_visible  BOOLEAN NOT NULL DEFAULT TRUE,
    is_required BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (config_id, field_code)
);

-- Чек-лист операционного закрытия заявки (US-K5).
CREATE TABLE project_config_checklist (
    id          BIGSERIAL PRIMARY KEY,
    config_id   BIGINT NOT NULL REFERENCES project_configs(id) ON DELETE CASCADE,
    item_code   TEXT   NOT NULL,   -- FACT | PHOTO | ACT | MAX | CUSTOMER_CLOSED | ACCEPTED
    name        TEXT   NOT NULL,
    is_required BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order  INT    NOT NULL DEFAULT 100,
    UNIQUE (config_id, item_code)
);

--changeset dipol:003-projects
CREATE TABLE projects (
    id                BIGSERIAL PRIMARY KEY,
    code              TEXT    NOT NULL UNIQUE,   -- АБ, АБ-ДОП, ШВ1, КЗ, СН, РК3, B2C …
    name              TEXT    NOT NULL,
    client_id         BIGINT  NOT NULL REFERENCES clients(id),
    config_id         BIGINT  NOT NULL REFERENCES project_configs(id),
    -- Версия конфигурации фиксируется на момент создания проекта.
    config_version    INT     NOT NULL DEFAULT 1,
    lifecycle_type    TEXT    NOT NULL CHECK (lifecycle_type IN ('PERMANENT','ONE_OFF')),
    started_on        DATE,
    finish_criteria   TEXT,                      -- обязателен для разового проекта
    finished_on       DATE,
    status            TEXT    NOT NULL DEFAULT 'ACTIVE'
                      CHECK (status IN ('ACTIVE','SUSPENDED','CLOSED')),
    responsible_employee_id BIGINT REFERENCES employees(id),
    season_from       DATE,                      -- сезон для снега/наледи (US-H5)
    season_to         DATE,
    contract_number   TEXT,
    note              TEXT,
    created_by        TEXT,
    created_at        TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_projects_client ON projects(client_id);
CREATE INDEX idx_projects_config ON projects(config_id);

-- Территория проекта: явный список объектов либо пусто (тогда любые дома).
CREATE TABLE project_buildings (
    project_id  BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    building_id BIGINT NOT NULL REFERENCES buildings(id),
    PRIMARY KEY (project_id, building_id)
);

-- Месячный план/лимит проекта: ФОТ, материалы, объём (US-E6, US-M2).
CREATE TABLE project_monthly_plans (
    id             BIGSERIAL PRIMARY KEY,
    project_id     BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    period_month   DATE   NOT NULL,       -- первое число месяца
    payroll_plan   NUMERIC(14,2),
    materials_plan NUMERIC(14,2),
    volume_plan    NUMERIC(14,3),
    volume_unit    TEXT REFERENCES units(code),
    note           TEXT,
    UNIQUE (project_id, period_month)
);

-- Смена проекта/статуса самого проекта — с автором и причиной.
CREATE TABLE project_status_history (
    id          BIGSERIAL PRIMARY KEY,
    project_id  BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    from_status TEXT,
    to_status   TEXT   NOT NULL,
    reason      TEXT,
    actor       TEXT,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);
