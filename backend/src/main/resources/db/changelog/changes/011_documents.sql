--liquibase formatted sql

-- ============================================================================
-- Эпик K: шаблоны документов с версиями, сформированные документы, сообщения
-- MAX, конструктор отчётов и закрытие месяца.
-- ============================================================================

--changeset dipol:011-templates
-- Поля шаблона берутся из сущностей ERP по именованным переменным, а не по
-- адресам ячеек (US-K1, US-N4). Старый документ хранит использованную версию.
CREATE TABLE document_templates (
    id                BIGSERIAL PRIMARY KEY,
    code              TEXT NOT NULL,
    name              TEXT NOT NULL,
    doc_type          TEXT NOT NULL CHECK (doc_type IN
                        ('ACT','PHOTO_REPORT','MESSAGE','WORK_ORDER','PERMIT','SPEC','REPORT')),
    project_config_id BIGINT REFERENCES project_configs(id),
    version           INT  NOT NULL DEFAULT 1,
    body              TEXT NOT NULL,          -- шаблон с плейсхолдерами {{request.number}}
    output_format     TEXT NOT NULL DEFAULT 'HTML'
                      CHECK (output_format IN ('HTML','TEXT','XLSX','DOCX')),
    valid_from        DATE NOT NULL DEFAULT CURRENT_DATE,
    valid_to          DATE,
    is_active         BOOLEAN NOT NULL DEFAULT TRUE,
    author            TEXT,
    created_at        TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (code, version)
);

--changeset dipol:011-documents
CREATE TABLE documents (
    id               BIGSERIAL PRIMARY KEY,
    template_id      BIGINT REFERENCES document_templates(id),
    template_version INT,
    doc_type         TEXT NOT NULL,
    number           TEXT,
    entity_type      TEXT NOT NULL,     -- REQUEST | TRIP | PROJECT | RK3 | PERIOD
    entity_id        BIGINT NOT NULL,
    request_id       BIGINT REFERENCES requests(id),
    project_id       BIGINT REFERENCES projects(id),
    -- Числовой объём в самом документе не правится: исправление идёт через
    -- факт/корректировку (US-K2). Здесь хранится снимок данных генерации.
    data_json        TEXT,
    body             TEXT,
    file_id          BIGINT REFERENCES files(id),
    signed_file_id   BIGINT REFERENCES files(id),
    status           TEXT NOT NULL DEFAULT 'GENERATED'
                     CHECK (status IN ('DRAFT','GENERATED','SENT','SIGNED','CANCELLED')),
    generated_by     TEXT,
    generated_at     TIMESTAMP NOT NULL DEFAULT NOW(),
    signed_at        TIMESTAMP
);
CREATE INDEX idx_docs_entity ON documents(entity_type, entity_id);
CREATE INDEX idx_docs_request ON documents(request_id);

--changeset dipol:011-reports
-- Конструктор отчётов (US-K7): сохранённый набор фильтров и колонок.
CREATE TABLE report_templates (
    id            BIGSERIAL PRIMARY KEY,
    name          TEXT NOT NULL,
    entity        TEXT NOT NULL,    -- REQUESTS | MATERIALS | PAYROLL | COMPENSATIONS | PROJECTS
    filters_json  TEXT,
    columns_json  TEXT,
    -- Внешний отчёт исключает исполнителей, внутренние комментарии и ФОТ.
    is_external   BOOLEAN NOT NULL DEFAULT FALSE,
    owner_user_id BIGINT REFERENCES users(id),
    is_shared     BOOLEAN NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMP NOT NULL DEFAULT NOW()
);

--changeset dipol:011-period-closure
-- Закрытие месяца (US-K6). После блокировки периода исправления выполняются
-- корректировками с правом и журналом.
CREATE TABLE period_closures (
    id           BIGSERIAL PRIMARY KEY,
    period_month DATE NOT NULL,
    project_id   BIGINT REFERENCES projects(id),
    status       TEXT NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','CLOSED','REOPENED')),
    closed_at    TIMESTAMP,
    closed_by    TEXT,
    checks_json  TEXT,           -- что было незакрыто на момент проверки
    note         TEXT,
    UNIQUE (period_month, project_id)
);
