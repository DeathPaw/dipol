--liquibase formatted sql

-- ============================================================================
-- Эпик B: заявка — основная операционная единица. Одна заявка — один адрес.
-- Проект, финансовый класс, статус выполнения и закрытие у заказчика — это
-- четыре РАЗНЫХ поля (US-B3): в legacy-таблицах они были смешаны в «Статус».
-- ============================================================================

--changeset dipol:005-dictionaries
CREATE TABLE request_sources (
    code TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    sort_order INT NOT NULL DEFAULT 100
);

CREATE TABLE financial_classes (
    code TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    sort_order INT NOT NULL DEFAULT 100
);

-- Причины ожидания. Ожидание фиксируется событием и НЕ создаёт отдельный
-- базовый статус заявки (US-B5, US-D2).
CREATE TABLE wait_reasons (
    code TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    sort_order INT NOT NULL DEFAULT 100
);

CREATE TABLE reschedule_reasons (
    code TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    sort_order INT NOT NULL DEFAULT 100
);

--changeset dipol:005-requests
CREATE TABLE requests (
    id                  BIGSERIAL PRIMARY KEY,
    -- Внутренний номер вида «АБ_2026_137»: проект + год + порядковый.
    number              TEXT    NOT NULL UNIQUE,
    seq_in_project      INT,
    -- Единое поле номера заявки заказчика (US-B2).
    external_number     TEXT,
    external_system     TEXT,                    -- СУПЕР МКД+ и т.п.
    source_code         TEXT    REFERENCES request_sources(code),

    client_id           BIGINT  NOT NULL REFERENCES clients(id),
    project_id          BIGINT  NOT NULL REFERENCES projects(id),
    building_id         BIGINT  NOT NULL REFERENCES buildings(id),
    zheu_id             BIGINT  REFERENCES zheu(id),

    apartment           TEXT,
    entrance            INT,                     -- рассчитывается из матрицы квартир
    entrance_is_manual  BOOLEAN NOT NULL DEFAULT FALSE,
    entrance_auto       INT,                     -- что предложила система (US-F2)
    entrance_fix_reason TEXT,
    place_note          TEXT,                    -- лоджия, козырёк над входом и т.п.

    received_at_customer TIMESTAMP,              -- дата заказчика, может быть пустой
    received_at         TIMESTAMP NOT NULL DEFAULT NOW(),   -- системная дата ERP
    planned_date        DATE,
    done_at             TIMESTAMP,               -- работы физически завершены
    customer_accepted_at DATE,                   -- приёмка заказчиком — отдельно от факта

    urgency             TEXT NOT NULL DEFAULT 'PLANNED' CHECK (urgency IN ('EMERGENCY','PLANNED')),
    is_warranty         BOOLEAN NOT NULL DEFAULT FALSE,
    parent_request_id   BIGINT REFERENCES requests(id),
    link_type           TEXT CHECK (link_type IN ('DUPLICATE','REPEAT','WARRANTY')),
    link_reason         TEXT,

    status              TEXT NOT NULL DEFAULT 'CREATED' REFERENCES request_statuses(code),
    financial_class     TEXT REFERENCES financial_classes(code),
    -- «Закрыта в программе» заказчика — отдельный признак, не статус ERP.
    customer_closed     BOOLEAN NOT NULL DEFAULT FALSE,
    is_closed           BOOLEAN NOT NULL DEFAULT FALSE,     -- операционное закрытие по чек-листу
    closed_at           TIMESTAMP,
    cancel_reason       TEXT,

    priority            INT NOT NULL DEFAULT 0,
    priority_reason     TEXT,

    description         TEXT,                    -- проблема со слов заказчика
    act_wording         TEXT,                    -- «Описание работ в акте»
    internal_comment    TEXT,

    responsible_employee_id BIGINT REFERENCES employees(id),
    contact_id          BIGINT REFERENCES client_contacts(id),
    contact_adhoc_name  TEXT,                    -- разовый контакт без карточки
    contact_adhoc_phone TEXT,

    -- Ссылки из legacy-таблиц, которые пока живут как внешние URL.
    report_link         TEXT,                    -- отчёт в MAX/ТГ
    photo_link          TEXT,
    report_text         TEXT,                    -- сгенерированный текст сообщения

    created_by          TEXT,
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_requests_project ON requests(project_id);
CREATE INDEX idx_requests_building ON requests(building_id);
CREATE INDEX idx_requests_status ON requests(status);
CREATE INDEX idx_requests_received ON requests(received_at DESC);
CREATE INDEX idx_requests_external ON requests(external_system, external_number);
CREATE INDEX idx_requests_parent ON requests(parent_request_id);

--changeset dipol:005-work-objects
-- Опциональный уровень между заявкой и SKU. Включается конфигурацией проекта.
CREATE TABLE work_objects (
    id          BIGSERIAL PRIMARY KEY,
    request_id  BIGINT NOT NULL REFERENCES requests(id) ON DELETE CASCADE,
    object_type TEXT   NOT NULL,   -- ROOF | FACADE | CANOPY | ENTRANCE | SEAM | OTHER
    name        TEXT   NOT NULL,
    note        TEXT,
    sort_order  INT NOT NULL DEFAULT 100
);

--changeset dipol:005-request-items
-- Позиция заявки = конкретная работа. Разные физические единицы никогда не
-- складываются в один «общий объём» (US-D3, US-H4): каждая позиция своя.
CREATE TABLE request_items (
    id             BIGSERIAL PRIMARY KEY,
    request_id     BIGINT NOT NULL REFERENCES requests(id) ON DELETE CASCADE,
    work_object_id BIGINT REFERENCES work_objects(id) ON DELETE SET NULL,
    sku_id         BIGINT NOT NULL REFERENCES sku(id),
    unit_code      TEXT   NOT NULL REFERENCES units(code),

    -- Этапы согласования объёма (US-G3): заявлено → выполнено → перемер → принято.
    planned_qty    NUMERIC(14,3),
    declared_qty   NUMERIC(14,3),
    fact_qty       NUMERIC(14,3),
    remeasured_qty NUMERIC(14,3),
    accepted_qty   NUMERIC(14,3),
    man_hours      NUMERIC(10,2),
    -- Справочный объём в другой единице: площадь козырька при учёте очистки
    -- в чел·ч (US-H2). В начислении не участвует.
    reference_qty  NUMERIC(14,3),
    reference_unit_code TEXT REFERENCES units(code),

    repair_type    TEXT,                 -- тип ремонта шва, тип козырька и т.п.
    status         TEXT NOT NULL DEFAULT 'PLANNED'
                   CHECK (status IN ('PLANNED','IN_PROGRESS','DONE','ON_REVIEW','ACCEPTED','REWORK','CANCELLED')),
    accepted_by    TEXT,
    accepted_at    TIMESTAMP,
    accept_comment TEXT,
    note           TEXT,
    sort_order     INT NOT NULL DEFAULT 100,
    created_at     TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_items_request ON request_items(request_id);
CREATE INDEX idx_items_sku ON request_items(sku_id);

-- Исполнители позиции: ответственный + произвольный состав (US-A5).
CREATE TABLE request_item_assignees (
    request_item_id BIGINT NOT NULL REFERENCES request_items(id) ON DELETE CASCADE,
    employee_id     BIGINT NOT NULL REFERENCES employees(id),
    role_code       TEXT REFERENCES employee_roles(code),
    is_responsible  BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (request_item_id, employee_id)
);

--changeset dipol:005-history
CREATE TABLE request_status_history (
    id          BIGSERIAL PRIMARY KEY,
    request_id  BIGINT NOT NULL REFERENCES requests(id) ON DELETE CASCADE,
    from_status TEXT,
    to_status   TEXT   NOT NULL,
    reason      TEXT,
    is_override BOOLEAN NOT NULL DEFAULT FALSE,   -- обход правила перехода
    actor       TEXT,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_rsh_request ON request_status_history(request_id, created_at);

-- Лента событий заявки (US-B6): создание, план, объём, документы, ожидания,
-- ручные комментарии. История не перезаписывается при изменении текущего поля.
CREATE TABLE request_events (
    id           BIGSERIAL PRIMARY KEY,
    request_id   BIGINT NOT NULL REFERENCES requests(id) ON DELETE CASCADE,
    event_type   TEXT   NOT NULL,   -- CREATED | STATUS | PLANNED | QTY | DOC | WAIT | COMMENT | MESSAGE | PROBLEM
    text         TEXT,
    wait_reason  TEXT REFERENCES wait_reasons(code),
    payload_json TEXT,
    actor        TEXT,
    created_at   TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_revents_request ON request_events(request_id, created_at DESC);

--changeset dipol:005-checklist
CREATE TABLE request_checklist (
    id             BIGSERIAL PRIMARY KEY,
    request_id     BIGINT NOT NULL REFERENCES requests(id) ON DELETE CASCADE,
    item_code      TEXT   NOT NULL,
    name           TEXT   NOT NULL,
    is_required    BOOLEAN NOT NULL DEFAULT TRUE,
    is_done        BOOLEAN NOT NULL DEFAULT FALSE,
    done_at        TIMESTAMP,
    done_by        TEXT,
    skipped_reason TEXT,
    UNIQUE (request_id, item_code)
);

--changeset dipol:005-import
CREATE TABLE import_batches (
    id          BIGSERIAL PRIMARY KEY,
    filename    TEXT,
    source      TEXT NOT NULL,       -- СУПЕР МКД+ | LEGACY_XLSX | MANUAL
    status      TEXT NOT NULL DEFAULT 'PREVIEW'
                CHECK (status IN ('PREVIEW','APPLIED','FAILED','CANCELLED')),
    mapping_json TEXT,               -- сопоставление колонок с полями ERP
    total_rows  INT NOT NULL DEFAULT 0,
    new_rows    INT NOT NULL DEFAULT 0,
    matched_rows INT NOT NULL DEFAULT 0,
    conflict_rows INT NOT NULL DEFAULT 0,
    created_by  TEXT,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    applied_at  TIMESTAMP
);

CREATE TABLE import_rows (
    id            BIGSERIAL PRIMARY KEY,
    batch_id      BIGINT NOT NULL REFERENCES import_batches(id) ON DELETE CASCADE,
    row_no        INT    NOT NULL,
    raw_json      TEXT   NOT NULL,
    match_status  TEXT   NOT NULL DEFAULT 'NEW'
                  CHECK (match_status IN ('NEW','MATCHED','AMBIGUOUS','ERROR','SKIPPED')),
    building_id   BIGINT REFERENCES buildings(id),
    request_id    BIGINT REFERENCES requests(id),
    candidates_json TEXT,            -- варианты адреса при неоднозначности
    error         TEXT,
    resolved_by   TEXT,
    resolved_at   TIMESTAMP
);
CREATE INDEX idx_import_rows_batch ON import_rows(batch_id, match_status);
