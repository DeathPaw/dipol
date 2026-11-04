--liquibase formatted sql

-- ============================================================================
-- Эпик P: техника безопасности. Отдельный контур с собственными правами,
-- версионируемыми шаблонами, электронными журналами и подтверждением
-- ознакомления работником в мобильном кабинете.
-- ============================================================================

--changeset dipol:012-safety-docs
CREATE TABLE safety_doc_types (
    code       TEXT PRIMARY KEY,
    name       TEXT NOT NULL,
    sort_order INT NOT NULL DEFAULT 100
);

CREATE TABLE safety_documents (
    id           BIGSERIAL PRIMARY KEY,
    type_code    TEXT NOT NULL REFERENCES safety_doc_types(code),
    title        TEXT NOT NULL,
    doc_number   TEXT,
    version      INT  NOT NULL DEFAULT 1,
    valid_from   DATE,
    valid_to     DATE,
    file_id      BIGINT REFERENCES files(id),
    -- Документ можно связать с видом работ, сотрудником, проектом, автомобилем.
    employee_id  BIGINT REFERENCES employees(id),
    project_id   BIGINT REFERENCES projects(id),
    vehicle_id   BIGINT REFERENCES vehicles(id),
    sku_id       BIGINT REFERENCES sku(id),
    note         TEXT,
    author       TEXT,
    created_at   TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_safetydocs_type ON safety_documents(type_code);

--changeset dipol:012-permits
-- Наряд-допуск на дату, объект, сотрудников и виды работ (US-P2).
CREATE TABLE work_permits (
    id               BIGSERIAL PRIMARY KEY,
    number           TEXT NOT NULL,
    permit_date      DATE NOT NULL,
    valid_to         DATE,
    building_id      BIGINT REFERENCES buildings(id),
    project_id       BIGINT REFERENCES projects(id),
    trip_id          BIGINT REFERENCES trips(id),
    template_id      BIGINT REFERENCES document_templates(id),
    template_version INT,
    work_kinds       TEXT,        -- кровля, высота, альпинизм, горелка, электроинструмент
    status           TEXT NOT NULL DEFAULT 'DRAFT'
                     CHECK (status IN ('DRAFT','ISSUED','CLOSED','CANCELLED')),
    issued_by        TEXT,
    issued_at        TIMESTAMP,
    closed_at        TIMESTAMP,
    file_id          BIGINT REFERENCES files(id),
    note             TEXT,
    created_at       TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_permits_date ON work_permits(permit_date DESC);

CREATE TABLE work_permit_requests (
    permit_id  BIGINT NOT NULL REFERENCES work_permits(id) ON DELETE CASCADE,
    request_id BIGINT NOT NULL REFERENCES requests(id) ON DELETE CASCADE,
    PRIMARY KEY (permit_id, request_id)
);

-- Подтверждение ознакомления фиксирует пользователя, время и версию (US-P5).
CREATE TABLE work_permit_employees (
    permit_id       BIGINT NOT NULL REFERENCES work_permits(id) ON DELETE CASCADE,
    employee_id     BIGINT NOT NULL REFERENCES employees(id),
    acknowledged_at TIMESTAMP,
    acknowledged_by TEXT,
    PRIMARY KEY (permit_id, employee_id)
);

--changeset dipol:012-journals
CREATE TABLE safety_journals (
    id        BIGSERIAL PRIMARY KEY,
    code      TEXT NOT NULL UNIQUE,
    name      TEXT NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE safety_journal_entries (
    id                      BIGSERIAL PRIMARY KEY,
    journal_id              BIGINT NOT NULL REFERENCES safety_journals(id) ON DELETE CASCADE,
    entry_date              DATE NOT NULL,
    employee_id             BIGINT REFERENCES employees(id),
    event                   TEXT NOT NULL,
    responsible_employee_id BIGINT REFERENCES employees(id),
    confirmed_at            TIMESTAMP,
    file_id                 BIGINT REFERENCES files(id),
    note                    TEXT,
    created_by              TEXT,
    created_at              TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_sje_journal ON safety_journal_entries(journal_id, entry_date DESC);

-- Подтверждение инструктажа вне наряда (плановые инструктажи, приказы).
CREATE TABLE safety_acknowledgements (
    id          BIGSERIAL PRIMARY KEY,
    document_id BIGINT NOT NULL REFERENCES safety_documents(id) ON DELETE CASCADE,
    employee_id BIGINT NOT NULL REFERENCES employees(id),
    doc_version INT,
    acknowledged_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (document_id, employee_id, doc_version)
);
