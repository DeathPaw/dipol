--liquibase formatted sql

-- ============================================================================
-- Мастер-данные: единицы измерения, ЖЭУ, МКД с адресной моделью, клиенты
-- и контактные лица, сотрудники, справочник работ/SKU.
-- ============================================================================

--changeset dipol:002-units
CREATE TABLE units (
    code       TEXT PRIMARY KEY,   -- M2, M3, MP, PCS, MH, SHIFT, KM, DEF
    name       TEXT NOT NULL,
    -- Физический вид величины. Складывать разные виды в «общий объём» запрещено
    -- (US-H4): агрегация допустима только внутри одного kind.
    kind       TEXT NOT NULL CHECK (kind IN ('AREA','VOLUME','LENGTH','COUNT','TIME','DISTANCE')),
    sort_order INT  NOT NULL DEFAULT 100
);

--changeset dipol:002-zheu
CREATE TABLE zheu (
    id         BIGSERIAL PRIMARY KEY,
    legacy_id  INT,                    -- ID_ЖЭУ из таблицы МКД
    name       TEXT NOT NULL,
    head_name  TEXT,                   -- ФИО начальника — подставляется в акты
    address    TEXT,
    phone      TEXT,
    email      TEXT,
    settlements TEXT,                  -- территория обслуживания
    is_active  BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

--changeset dipol:002-buildings
-- Главный ключ дома — внутренний ID (US-A1/US-A6). ФИАС и КЛАДР хранятся как
-- внешние идентификаторы, текстовый адрес ключом не является.
CREATE TABLE buildings (
    id                  BIGSERIAL PRIMARY KEY,
    legacy_id           INT,                       -- ID_Дома из листа «МКД»
    address             TEXT    NOT NULL,          -- нормализованное представление
    address_key         TEXT    NOT NULL,          -- ключ сопоставления (lower, без пунктуации)
    fias_id             TEXT,
    kladr               TEXT,
    zheu_id             BIGINT REFERENCES zheu(id),
    floors              INT,
    entrances_count     INT,
    apartments_count    INT,
    apartments_per_entrance INT,
    roof_type           TEXT,
    condition_note      TEXT,
    roof_access         TEXT,
    attic_access        TEXT,
    basement_access     TEXT,
    access_note         TEXT,
    lat                 NUMERIC(10,6),
    lon                 NUMERIC(10,6),
    is_active           BOOLEAN NOT NULL DEFAULT TRUE,
    -- Использованный в истории дом не удаляют — его объединяют с дублем.
    merged_into_id      BIGINT REFERENCES buildings(id),
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_buildings_key ON buildings(address_key);
CREATE INDEX idx_buildings_zheu ON buildings(zheu_id);

-- Любое написание адреса из внешней системы, запомненное за домом (US-A6).
CREATE TABLE building_aliases (
    id           BIGSERIAL PRIMARY KEY,
    building_id  BIGINT NOT NULL REFERENCES buildings(id) ON DELETE CASCADE,
    alias        TEXT   NOT NULL,
    alias_key    TEXT   NOT NULL,
    source       TEXT   NOT NULL DEFAULT 'MANUAL',   -- СУПЕР МКД+ | ИМПОРТ | LEGACY | MANUAL
    created_by   TEXT,
    created_at   TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (alias_key, source)
);
CREATE INDEX idx_alias_building ON building_aliases(building_id);

-- Матрица квартир по подъездам: из неё считается подъезд заявки (US-F2).
CREATE TABLE building_entrances (
    id             BIGSERIAL PRIMARY KEY,
    building_id    BIGINT NOT NULL REFERENCES buildings(id) ON DELETE CASCADE,
    number         INT    NOT NULL,
    apartment_from INT,
    apartment_to   INT,
    floors         INT,
    note           TEXT,
    UNIQUE (building_id, number)
);

-- История смены ЖЭУ у дома: печатные формы берут ЖЭУ на дату документа (US-A2).
CREATE TABLE building_zheu_history (
    id          BIGSERIAL PRIMARY KEY,
    building_id BIGINT NOT NULL REFERENCES buildings(id) ON DELETE CASCADE,
    zheu_id     BIGINT NOT NULL REFERENCES zheu(id),
    valid_from  DATE   NOT NULL,
    valid_to    DATE,
    actor       TEXT
);

--changeset dipol:002-clients
CREATE TABLE clients (
    id             BIGSERIAL PRIMARY KEY,
    client_type    TEXT NOT NULL CHECK (client_type IN ('LEGAL','IP','PERSON')),
    name           TEXT NOT NULL,              -- наименование или ФИО
    short_name     TEXT,
    inn            TEXT,
    kpp            TEXT,
    ogrn           TEXT,
    legal_address  TEXT,
    actual_address TEXT,
    bank_name      TEXT,
    bank_bik       TEXT,
    bank_account   TEXT,
    corr_account   TEXT,
    phone          TEXT,
    email          TEXT,
    comment        TEXT,
    is_active      BOOLEAN NOT NULL DEFAULT TRUE,
    merged_into_id BIGINT REFERENCES clients(id),
    created_at     TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_clients_name ON clients(lower(name));

CREATE TABLE client_contacts (
    id          BIGSERIAL PRIMARY KEY,
    client_id   BIGINT NOT NULL REFERENCES clients(id) ON DELETE CASCADE,
    full_name   TEXT   NOT NULL,
    position    TEXT,
    contact_role TEXT,           -- производство | акты | доступы | оплата | руководитель
    phone       TEXT,
    email       TEXT,
    messenger   TEXT,
    zheu_id     BIGINT REFERENCES zheu(id),
    comment     TEXT,
    is_active   BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_contacts_client ON client_contacts(client_id);

--changeset dipol:002-employees
CREATE TABLE employee_roles (
    code       TEXT PRIMARY KEY,   -- ROOFER, ROPE_ACCESS, DRIVER, FOREMAN, HELPER, SUPPLY, SAFETY
    name       TEXT NOT NULL,
    sort_order INT  NOT NULL DEFAULT 100
);

CREATE TABLE employees (
    id          BIGSERIAL PRIMARY KEY,
    full_name   TEXT    NOT NULL,
    short_name  TEXT,                       -- как человек подписан в таблицах: «Мухтар», «Руслан»
    role_code   TEXT REFERENCES employee_roles(code),
    position    TEXT,
    phone       TEXT,
    email       TEXT,
    hired_on    DATE,
    fired_on    DATE,
    is_active   BOOLEAN NOT NULL DEFAULT TRUE,
    note        TEXT,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_employees_name ON employees(lower(full_name));

ALTER TABLE users ADD CONSTRAINT fk_users_employee
    FOREIGN KEY (employee_id) REFERENCES employees(id);

-- Специализации — какие работы сотрудник выполняет. Жёсткой сущности «бригада»
-- на старте нет (US-A5): состав всегда фиксируется конкретными людьми.
CREATE TABLE employee_specializations (
    employee_id BIGINT NOT NULL REFERENCES employees(id) ON DELETE CASCADE,
    sku_id      BIGINT NOT NULL,
    PRIMARY KEY (employee_id, sku_id)
);

-- Шаблон состава («Бригада 1 (К)») — только подсказка при планировании.
CREATE TABLE crew_templates (
    id        BIGSERIAL PRIMARY KEY,
    name      TEXT NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE TABLE crew_template_members (
    crew_id     BIGINT NOT NULL REFERENCES crew_templates(id) ON DELETE CASCADE,
    employee_id BIGINT NOT NULL REFERENCES employees(id),
    PRIMARY KEY (crew_id, employee_id)
);

--changeset dipol:002-sku
-- Справочник работ. Расчёты идут по ID работы, отображаемое название ключом
-- тарифа не является (US-A4).
CREATE TABLE sku_groups (
    id         BIGSERIAL PRIMARY KEY,
    name       TEXT NOT NULL,
    sort_order INT NOT NULL DEFAULT 100
);

CREATE TABLE sku (
    id             BIGSERIAL PRIMARY KEY,
    code           TEXT NOT NULL UNIQUE,
    name           TEXT NOT NULL,
    group_id       BIGINT REFERENCES sku_groups(id),
    unit_code      TEXT NOT NULL REFERENCES units(code),
    act_wording    TEXT,                     -- формулировка для акта
    requires_photo BOOLEAN NOT NULL DEFAULT FALSE,
    is_active      BOOLEAN NOT NULL DEFAULT TRUE,
    description    TEXT,
    sort_order     INT NOT NULL DEFAULT 100,
    created_at     TIMESTAMP NOT NULL DEFAULT NOW()
);

ALTER TABLE employee_specializations ADD CONSTRAINT fk_spec_sku
    FOREIGN KEY (sku_id) REFERENCES sku(id) ON DELETE CASCADE;
