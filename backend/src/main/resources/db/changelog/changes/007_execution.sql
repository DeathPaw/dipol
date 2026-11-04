--liquibase formatted sql

-- ============================================================================
-- Эпик D: исполнение. Старт/завершение работы, фактические часы, фото,
-- проблемы и передача результата на приёмку.
-- ============================================================================

--changeset dipol:007-work-sessions
-- Старт и завершение работы конкретным сотрудником (US-D2). Правка времени
-- прорабом требует причины, исходное значение остаётся в audit_log.
CREATE TABLE work_sessions (
    id              BIGSERIAL PRIMARY KEY,
    request_item_id BIGINT NOT NULL REFERENCES request_items(id) ON DELETE CASCADE,
    employee_id     BIGINT NOT NULL REFERENCES employees(id),
    trip_id         BIGINT REFERENCES trips(id),
    started_at      TIMESTAMP NOT NULL,
    finished_at     TIMESTAMP,
    man_hours       NUMERIC(10,2),
    travel_hours    NUMERIC(10,2),      -- дорога хранится отдельно от работы (US-G6)
    comment         TEXT,
    corrected_by    TEXT,
    correction_reason TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_wsessions_item ON work_sessions(request_item_id);
CREATE INDEX idx_wsessions_employee ON work_sessions(employee_id, started_at DESC);

--changeset dipol:007-photos
-- Фото хранит автора, время, объект и тип. Ссылка на внешнее хранилище
-- допускается, но связь с записью в ERP устойчива (US-D4).
CREATE TABLE photos (
    id           BIGSERIAL PRIMARY KEY,
    entity_type  TEXT   NOT NULL,     -- REQUEST | REQUEST_ITEM | TRIP | BUILDING | PROBLEM
    entity_id    BIGINT NOT NULL,
    file_id      BIGINT REFERENCES files(id),
    external_url TEXT,
    photo_kind   TEXT NOT NULL DEFAULT 'OTHER'
                 CHECK (photo_kind IN ('BEFORE','AFTER','DEFECT','HIDDEN_WORK','OTHER')),
    comment      TEXT,
    author       TEXT,
    created_at   TIMESTAMP NOT NULL DEFAULT NOW(),
    CHECK (file_id IS NOT NULL OR external_url IS NOT NULL)
);
CREATE INDEX idx_photos_entity ON photos(entity_type, entity_id);

--changeset dipol:007-problems
CREATE TABLE problem_categories (
    code TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    sort_order INT NOT NULL DEFAULT 100
);

CREATE TABLE problems (
    id              BIGSERIAL PRIMARY KEY,
    request_id      BIGINT REFERENCES requests(id) ON DELETE CASCADE,
    request_item_id BIGINT REFERENCES request_items(id) ON DELETE CASCADE,
    category_code   TEXT NOT NULL REFERENCES problem_categories(code),
    description     TEXT NOT NULL,
    responsible_employee_id BIGINT REFERENCES employees(id),
    status          TEXT NOT NULL DEFAULT 'OPEN'
                    CHECK (status IN ('OPEN','IN_PROGRESS','RESOLVED','CANCELLED')),
    blocks_work     BOOLEAN NOT NULL DEFAULT FALSE,
    resolution      TEXT,
    extra_hours     NUMERIC(10,2),
    created_by      TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    closed_at       TIMESTAMP
);
CREATE INDEX idx_problems_request ON problems(request_id);
CREATE INDEX idx_problems_open ON problems(status) WHERE status IN ('OPEN','IN_PROGRESS');
