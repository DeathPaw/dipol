--liquibase formatted sql

-- ============================================================================
-- Эпик Q: автомобили, ТО/страховка, расходы, собственное оборудование
-- и норматив производственной амортизации.
-- ============================================================================

--changeset dipol:004-vehicles
CREATE TABLE vehicles (
    id                  BIGSERIAL PRIMARY KEY,
    brand               TEXT NOT NULL,
    model               TEXT,
    plate               TEXT NOT NULL UNIQUE,
    status              TEXT NOT NULL DEFAULT 'ACTIVE'
                        CHECK (status IN ('ACTIVE','SERVICE','SOLD','INACTIVE')),
    assigned_employee_id BIGINT REFERENCES employees(id),
    mileage             INT,
    note                TEXT,
    is_active           BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP NOT NULL DEFAULT NOW()
);

-- ТО, ремонты и страховка в одной таблице: различаются видом и наличием
-- периода действия. Просроченные контрольные даты подсвечиваются (US-Q3).
CREATE TABLE vehicle_maintenance (
    id          BIGSERIAL PRIMARY KEY,
    vehicle_id  BIGINT NOT NULL REFERENCES vehicles(id) ON DELETE CASCADE,
    kind        TEXT   NOT NULL CHECK (kind IN ('SERVICE','REPAIR','INSURANCE','INSPECTION')),
    occurred_on DATE   NOT NULL,
    mileage     INT,
    works       TEXT,
    cost        NUMERIC(12,2),
    provider    TEXT,
    valid_from  DATE,
    valid_to    DATE,
    note        TEXT,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_vmaint_vehicle ON vehicle_maintenance(vehicle_id, occurred_on DESC);

CREATE TABLE vehicle_expense_categories (
    code TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    sort_order INT NOT NULL DEFAULT 100
);

CREATE TABLE vehicle_expenses (
    id            BIGSERIAL PRIMARY KEY,
    vehicle_id    BIGINT NOT NULL REFERENCES vehicles(id) ON DELETE CASCADE,
    category_code TEXT   NOT NULL REFERENCES vehicle_expense_categories(code),
    spent_on      DATE   NOT NULL,
    amount        NUMERIC(12,2) NOT NULL,
    project_id    BIGINT REFERENCES projects(id),
    trip_id       BIGINT,          -- FK ставится в 006 после trips
    file_id       BIGINT REFERENCES files(id),
    note          TEXT,
    created_by    TEXT,
    created_at    TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_vexp_vehicle ON vehicle_expenses(vehicle_id, spent_on DESC);

--changeset dipol:004-equipment
CREATE TABLE equipment (
    id            BIGSERIAL PRIMARY KEY,
    inventory_no  TEXT UNIQUE,
    name          TEXT NOT NULL,
    equipment_type TEXT,
    status        TEXT NOT NULL DEFAULT 'ACTIVE'
                  CHECK (status IN ('ACTIVE','REPAIR','WRITTEN_OFF')),
    assigned_employee_id BIGINT REFERENCES employees(id),
    purchase_cost NUMERIC(12,2),
    purchased_on  DATE,
    note          TEXT,
    created_at    TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Норматив амортизации для производственной себестоимости (US-Q6, US-M5).
-- Может задаваться на оборудование, на конфигурацию проекта или на SKU.
CREATE TABLE depreciation_norms (
    id                BIGSERIAL PRIMARY KEY,
    equipment_id      BIGINT REFERENCES equipment(id),
    project_config_id BIGINT REFERENCES project_configs(id),
    sku_id            BIGINT REFERENCES sku(id),
    basis             TEXT NOT NULL CHECK (basis IN ('SHIFT','HOUR','UNIT')),
    amount            NUMERIC(12,2) NOT NULL,
    valid_from        DATE NOT NULL,
    valid_to          DATE,
    author            TEXT,
    created_at        TIMESTAMP NOT NULL DEFAULT NOW()
);
