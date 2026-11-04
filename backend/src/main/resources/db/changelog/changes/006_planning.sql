--liquibase formatted sql

-- ============================================================================
-- Эпик C: выезды (M:N с заявками), маршрут и пробег, спецтехника как
-- независимая смена с распределением стоимости по адресам.
-- ============================================================================

--changeset dipol:006-trips
CREATE TABLE trips (
    id                      BIGSERIAL PRIMARY KEY,
    trip_date               DATE NOT NULL,
    slot_from               TIME,
    slot_to                 TIME,
    responsible_employee_id BIGINT REFERENCES employees(id),
    vehicle_id              BIGINT REFERENCES vehicles(id),
    crew_template_id        BIGINT REFERENCES crew_templates(id),
    -- У выезда короткий собственный жизненный цикл: он не подменяет статус заявки.
    status                  TEXT NOT NULL DEFAULT 'PLANNED'
                            CHECK (status IN ('PLANNED','IN_PROGRESS','DONE','CANCELLED')),
    mileage_total           NUMERIC(10,2),
    mileage_source          TEXT CHECK (mileage_source IN ('MAP','MANUAL')),
    route_note              TEXT,
    note                    TEXT,
    created_by              TEXT,
    created_at              TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_trips_date ON trips(trip_date DESC);

ALTER TABLE vehicle_expenses ADD CONSTRAINT fk_vexp_trip
    FOREIGN KEY (trip_id) REFERENCES trips(id);

-- Одна заявка может выполняться несколькими выездами, один выезд закрывает
-- несколько заявок разных адресов (US-C1, US-C2).
CREATE TABLE trip_requests (
    trip_id    BIGINT NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
    request_id BIGINT NOT NULL REFERENCES requests(id) ON DELETE CASCADE,
    sort_order INT NOT NULL DEFAULT 100,
    PRIMARY KEY (trip_id, request_id)
);

-- Плановый и фактический состав хранятся раздельно: замена участника не
-- меняет исторический состав других выездов (US-C3).
CREATE TABLE trip_members (
    trip_id     BIGINT NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
    employee_id BIGINT NOT NULL REFERENCES employees(id),
    is_planned  BOOLEAN NOT NULL DEFAULT TRUE,
    is_fact     BOOLEAN NOT NULL DEFAULT FALSE,
    role_code   TEXT REFERENCES employee_roles(code),
    PRIMARY KEY (trip_id, employee_id)
);

-- Маршрут: адреса заявок плюс промежуточные точки (склад, поставщик).
CREATE TABLE trip_points (
    id          BIGSERIAL PRIMARY KEY,
    trip_id     BIGINT NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
    sort_order  INT    NOT NULL DEFAULT 100,
    point_kind  TEXT   NOT NULL CHECK (point_kind IN ('REQUEST','WAREHOUSE','SUPPLIER','BASE','OTHER')),
    request_id  BIGINT REFERENCES requests(id),
    building_id BIGINT REFERENCES buildings(id),
    name        TEXT,
    lat         NUMERIC(10,6),
    lon         NUMERIC(10,6)
);

-- Распределение пробега выезда по заявкам (US-Q5). Сумма контролируется
-- относительно mileage_total.
CREATE TABLE trip_mileage_allocations (
    id         BIGSERIAL PRIMARY KEY,
    trip_id    BIGINT NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
    request_id BIGINT NOT NULL REFERENCES requests(id) ON DELETE CASCADE,
    km         NUMERIC(10,2) NOT NULL,
    is_manual  BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (trip_id, request_id)
);

-- Перенос даты/состава/автомобиля — всегда с причиной (US-C6).
CREATE TABLE trip_reschedules (
    id           BIGSERIAL PRIMARY KEY,
    trip_id      BIGINT REFERENCES trips(id) ON DELETE CASCADE,
    request_id   BIGINT REFERENCES requests(id) ON DELETE CASCADE,
    old_date     DATE,
    new_date     DATE,
    reason_code  TEXT REFERENCES reschedule_reasons(code),
    comment      TEXT,
    actor        TEXT,
    created_at   TIMESTAMP NOT NULL DEFAULT NOW()
);

--changeset dipol:006-special-equipment
CREATE TABLE special_equipment_types (
    code TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    default_resource_unit TEXT REFERENCES units(code),
    sort_order INT NOT NULL DEFAULT 100
);

-- ПУХТО, кран, вышка, манипулятор: отдельная смена/привлечение, НЕ атрибут
-- выезда (US-C4). Связь с выездом опциональна.
CREATE TABLE equipment_shifts (
    id              BIGSERIAL PRIMARY KEY,
    type_code       TEXT NOT NULL REFERENCES special_equipment_types(code),
    supplier_id     BIGINT,              -- FK на suppliers ставится в 008
    supplier_name   TEXT,                -- если контрагент ещё не заведён
    date_from       TIMESTAMP NOT NULL,
    date_to         TIMESTAMP,
    resource_qty    NUMERIC(12,2),       -- часы, рейсы, смены
    resource_unit   TEXT REFERENCES units(code),
    planned_cost    NUMERIC(12,2),
    actual_cost     NUMERIC(12,2),
    trip_id         BIGINT REFERENCES trips(id),
    project_id      BIGINT REFERENCES projects(id),
    note            TEXT,
    created_by      TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_eshift_date ON equipment_shifts(date_from DESC);

-- Одно привлечение распределяется между адресами/заявками/проектами суммой,
-- процентом, часами или рейсами. Нераспределённый остаток виден в контроле
-- качества данных.
CREATE TABLE equipment_shift_allocations (
    id          BIGSERIAL PRIMARY KEY,
    shift_id    BIGINT NOT NULL REFERENCES equipment_shifts(id) ON DELETE CASCADE,
    building_id BIGINT REFERENCES buildings(id),
    request_id  BIGINT REFERENCES requests(id),
    project_id  BIGINT REFERENCES projects(id),
    basis       TEXT NOT NULL CHECK (basis IN ('AMOUNT','PERCENT','HOURS','RUNS','MANUAL')),
    basis_value NUMERIC(12,3),
    amount      NUMERIC(12,2) NOT NULL,
    note        TEXT
);
CREATE INDEX idx_eshift_alloc ON equipment_shift_allocations(shift_id);
