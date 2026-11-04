--liquibase formatted sql

-- ============================================================================
-- Эпик I: номенклатура, поставщики, заказы, единый складской журнал движений
-- (включая сценарий «с колёс») и распределение закупки по проектам/адресам.
-- ============================================================================

--changeset dipol:008-catalog
CREATE TABLE material_groups (
    id         BIGSERIAL PRIMARY KEY,
    name       TEXT NOT NULL,
    sort_order INT  NOT NULL DEFAULT 100
);

CREATE TABLE materials (
    id             BIGSERIAL PRIMARY KEY,
    code           TEXT UNIQUE,
    name           TEXT NOT NULL,
    group_id       BIGINT REFERENCES material_groups(id),
    unit_code      TEXT NOT NULL REFERENCES units(code),
    external_1c_code TEXT,
    area_per_unit  NUMERIC(10,3),          -- м² в рулоне: нужен для цены за м²
    is_active      BOOLEAN NOT NULL DEFAULT TRUE,
    -- Дубль объединяется с сохранением истории старого ID (US-I1).
    merged_into_id BIGINT REFERENCES materials(id),
    note           TEXT,
    created_at     TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_materials_name ON materials(lower(name));

CREATE TABLE material_aliases (
    id          BIGSERIAL PRIMARY KEY,
    material_id BIGINT NOT NULL REFERENCES materials(id) ON DELETE CASCADE,
    alias       TEXT   NOT NULL,
    alias_key   TEXT   NOT NULL UNIQUE,
    source      TEXT
);

CREATE TABLE suppliers (
    id         BIGSERIAL PRIMARY KEY,
    name       TEXT NOT NULL,
    inn        TEXT,
    phone      TEXT,
    email      TEXT,
    contact    TEXT,
    terms      TEXT,
    is_active  BOOLEAN NOT NULL DEFAULT TRUE,
    note       TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

ALTER TABLE equipment_shifts ADD CONSTRAINT fk_eshift_supplier
    FOREIGN KEY (supplier_id) REFERENCES suppliers(id);

-- История цены по материалу/поставщику (US-I2).
CREATE TABLE material_prices (
    id           BIGSERIAL PRIMARY KEY,
    material_id  BIGINT NOT NULL REFERENCES materials(id) ON DELETE CASCADE,
    supplier_id  BIGINT REFERENCES suppliers(id),
    priced_on    DATE   NOT NULL,
    price        NUMERIC(12,2) NOT NULL,
    price_per_m2 NUMERIC(12,2),
    order_number TEXT,
    created_at   TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_mprices ON material_prices(material_id, priced_on DESC);

--changeset dipol:008-orders
-- Заказ поставщику со строками: номер больше не дублируется ради разнесения
-- по проектам — для этого есть material_allocations (US-I3, US-I4).
CREATE TABLE purchase_orders (
    id           BIGSERIAL PRIMARY KEY,
    number       TEXT NOT NULL,
    supplier_id  BIGINT REFERENCES suppliers(id),
    ordered_at   TIMESTAMP NOT NULL,
    status       TEXT NOT NULL DEFAULT 'ORDERED'
                 CHECK (status IN ('DRAFT','ORDERED','RECEIVED','PARTIAL','CANCELLED')),
    total_amount NUMERIC(14,2),
    note         TEXT,
    created_by   TEXT,
    created_at   TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (supplier_id, number)
);

CREATE TABLE purchase_order_lines (
    id          BIGSERIAL PRIMARY KEY,
    order_id    BIGINT NOT NULL REFERENCES purchase_orders(id) ON DELETE CASCADE,
    material_id BIGINT NOT NULL REFERENCES materials(id),
    qty         NUMERIC(14,3) NOT NULL,
    unit_code   TEXT NOT NULL REFERENCES units(code),
    price       NUMERIC(12,2),
    amount      NUMERIC(14,2),
    note        TEXT
);

--changeset dipol:008-stock
-- Единый журнал движений. Отмена оформляется ОБРАТНЫМ движением, исходная
-- операция не удаляется (US-I6). Сценарий «с колёс» — поступление плюс
-- немедленное связанное списание без физического хранения (US-I5).
CREATE TABLE stock_movements (
    id              BIGSERIAL PRIMARY KEY,
    movement_type   TEXT   NOT NULL CHECK (movement_type IN ('RECEIPT','ISSUE','RETURN','ADJUST')),
    material_id     BIGINT NOT NULL REFERENCES materials(id),
    qty             NUMERIC(14,3) NOT NULL,     -- всегда положительное; направление в типе
    unit_code       TEXT   NOT NULL REFERENCES units(code),
    price           NUMERIC(12,2),
    amount          NUMERIC(14,2),
    moved_at        TIMESTAMP NOT NULL DEFAULT NOW(),
    supplier_id     BIGINT REFERENCES suppliers(id),
    order_line_id   BIGINT REFERENCES purchase_order_lines(id),
    -- Списание относится к самому детальному доступному уровню; агрегация
    -- до заявки/проекта/адреса/клиента считается запросом, а не полем.
    project_id      BIGINT REFERENCES projects(id),
    request_id      BIGINT REFERENCES requests(id),
    request_item_id BIGINT REFERENCES request_items(id),
    building_id     BIGINT REFERENCES buildings(id),
    employee_id     BIGINT REFERENCES employees(id),
    on_wheels       BOOLEAN NOT NULL DEFAULT FALSE,
    linked_movement_id BIGINT REFERENCES stock_movements(id),  -- пара «поступление↔списание»
    reverses_id     BIGINT REFERENCES stock_movements(id),     -- сторнирующее движение
    comment         TEXT,
    created_by      TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_stock_material ON stock_movements(material_id, moved_at);
CREATE INDEX idx_stock_project ON stock_movements(project_id, moved_at);
CREATE INDEX idx_stock_request ON stock_movements(request_id);

-- Разнесение одной строки заказа/поступления по проектам и адресам.
CREATE TABLE material_allocations (
    id            BIGSERIAL PRIMARY KEY,
    order_line_id BIGINT REFERENCES purchase_order_lines(id) ON DELETE CASCADE,
    movement_id   BIGINT REFERENCES stock_movements(id) ON DELETE CASCADE,
    project_id    BIGINT REFERENCES projects(id),
    building_id   BIGINT REFERENCES buildings(id),
    request_id    BIGINT REFERENCES requests(id),
    qty           NUMERIC(14,3),
    amount        NUMERIC(14,2),
    basis         TEXT,
    note          TEXT,
    created_by    TEXT,
    created_at    TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_mallocs_line ON material_allocations(order_line_id);

-- План материалов (РК-3): хранится раздельно от факта.
CREATE TABLE material_plans (
    id          BIGSERIAL PRIMARY KEY,
    project_id  BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    building_id BIGINT REFERENCES buildings(id),
    request_id  BIGINT REFERENCES requests(id),
    material_id BIGINT NOT NULL REFERENCES materials(id),
    qty         NUMERIC(14,3) NOT NULL,
    unit_code   TEXT NOT NULL REFERENCES units(code),
    amount      NUMERIC(14,2),
    note        TEXT
);
