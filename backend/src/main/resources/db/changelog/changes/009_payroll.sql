--liquibase formatted sql

-- ============================================================================
-- Эпик J + E: ФОТ. Ключевое решение владельца процесса (30.08.2026): схема
-- оплаты назначается связке «сотрудник + конкретный проект + период», а не
-- проекту целиком. В одном проекте одновременно допустимы сменная и
-- сдельная/KPI-схемы у разных людей.
-- ============================================================================

--changeset dipol:009-pay-terms
CREATE TABLE pay_scheme_types (
    code       TEXT PRIMARY KEY,   -- SHIFT | HOUR | PIECE | PIECE_KPI | FIX | MIXED
    name       TEXT NOT NULL,
    -- Единица, от которой считается начисление. Нужна, чтобы «за смену» не
    -- пыталось умножаться на м².
    basis_unit TEXT REFERENCES units(code),
    sort_order INT NOT NULL DEFAULT 100
);

-- Индивидуальное условие оплаты. Изменение ставки/схемы = новая версия;
-- закрытые начисления ссылаются на свою версию и не пересчитываются.
CREATE TABLE employee_pay_terms (
    id           BIGSERIAL PRIMARY KEY,
    employee_id  BIGINT NOT NULL REFERENCES employees(id),
    project_id   BIGINT NOT NULL REFERENCES projects(id),
    scheme_code  TEXT   NOT NULL REFERENCES pay_scheme_types(code),
    version      INT    NOT NULL DEFAULT 1,
    valid_from   DATE   NOT NULL,
    valid_to     DATE,
    rate         NUMERIC(12,2),        -- ставка за смену/час/единицу
    formula_code TEXT,                 -- код версионируемой формулы для KPI
    params_json  TEXT,                 -- параметры формулы
    is_active    BOOLEAN NOT NULL DEFAULT TRUE,
    author       TEXT,
    comment      TEXT,
    created_at   TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_paytermss ON employee_pay_terms(employee_id, project_id, valid_from DESC);

--changeset dipol:009-rates
-- Тарифы по работе/роли/сотруднику/проекту с датой действия. Приоритет правил
-- задан явно, чтобы одинаковое название работы не давало разных ставок (US-J3).
CREATE TABLE payroll_rates (
    id                BIGSERIAL PRIMARY KEY,
    sku_id            BIGINT REFERENCES sku(id),
    project_id        BIGINT REFERENCES projects(id),
    project_config_id BIGINT REFERENCES project_configs(id),
    employee_role_code TEXT REFERENCES employee_roles(code),
    employee_id       BIGINT REFERENCES employees(id),
    unit_code         TEXT NOT NULL REFERENCES units(code),
    value             NUMERIC(12,2) NOT NULL,
    valid_from        DATE NOT NULL,
    valid_to          DATE,
    -- Чем уже правило, тем выше приоритет. Пересечения без приоритета
    -- запрещены на уровне сервиса с предупреждением.
    priority          INT NOT NULL DEFAULT 100,
    author            TEXT,
    comment           TEXT,
    created_at        TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_rates_lookup ON payroll_rates(sku_id, project_id, valid_from DESC);

-- Диапазонные коэффициенты с ЯВНЫМИ операторами включения границ (US-J3, US-F6).
CREATE TABLE coefficient_ranges (
    id             BIGSERIAL PRIMARY KEY,
    code           TEXT NOT NULL,          -- RK3_DEMOLITION_K и т.п.
    from_value     NUMERIC(12,4),
    from_inclusive BOOLEAN NOT NULL DEFAULT TRUE,
    to_value       NUMERIC(12,4),
    to_inclusive   BOOLEAN NOT NULL DEFAULT FALSE,
    k              NUMERIC(12,4) NOT NULL,
    valid_from     DATE NOT NULL,
    valid_to       DATE,
    author         TEXT,
    comment        TEXT
);
CREATE INDEX idx_coef_code ON coefficient_ranges(code, valid_from DESC);

--changeset dipol:009-entries
-- Начисление. Хранит исходные данные расчёта, применённую версию правила и
-- результат — чтобы сумму можно было раскрыть и воспроизвести (US-J2, US-E5).
CREATE TABLE payroll_entries (
    id               BIGSERIAL PRIMARY KEY,
    employee_id      BIGINT NOT NULL REFERENCES employees(id),
    project_id       BIGINT NOT NULL REFERENCES projects(id),
    request_id       BIGINT REFERENCES requests(id),
    request_item_id  BIGINT REFERENCES request_items(id),
    trip_id          BIGINT REFERENCES trips(id),
    rk3_object_id    BIGINT,                      -- FK ставится в 013
    basis_type       TEXT NOT NULL CHECK (basis_type IN ('FACT','SHIFT','MANUAL','RK3','ADJUSTMENT')),
    period_month     DATE NOT NULL,               -- первое число месяца
    entry_date       DATE NOT NULL,
    sku_id           BIGINT REFERENCES sku(id),
    qty              NUMERIC(14,3),
    unit_code        TEXT REFERENCES units(code),
    rate             NUMERIC(12,2),
    coefficient      NUMERIC(12,4) NOT NULL DEFAULT 1,
    formula          TEXT,                        -- текстовая расшифровка расчёта
    amount           NUMERIC(14,2) NOT NULL,
    pay_terms_id     BIGINT REFERENCES employee_pay_terms(id),
    pay_terms_version INT,
    rate_id          BIGINT REFERENCES payroll_rates(id),
    status           TEXT NOT NULL DEFAULT 'DRAFT'
                     CHECK (status IN ('DRAFT','APPROVED','CLOSED','REVERSED')),
    is_manual        BOOLEAN NOT NULL DEFAULT FALSE,
    reason           TEXT,
    reverses_id      BIGINT REFERENCES payroll_entries(id),
    created_by       TEXT,
    created_at       TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_payroll_emp ON payroll_entries(employee_id, period_month);
CREATE INDEX idx_payroll_project ON payroll_entries(project_id, period_month);

-- Премии, штрафы, авансы, прорабские, ручные корректировки — отдельно от
-- расчётного ФОТ, всегда с основанием и автором.
CREATE TABLE payroll_adjustments (
    id           BIGSERIAL PRIMARY KEY,
    employee_id  BIGINT NOT NULL REFERENCES employees(id),
    project_id   BIGINT REFERENCES projects(id),
    period_month DATE   NOT NULL,
    adj_date     DATE   NOT NULL,
    kind         TEXT   NOT NULL CHECK (kind IN ('BONUS','PENALTY','ADVANCE','CORRECTION','FOREMAN')),
    amount       NUMERIC(14,2) NOT NULL,
    reason       TEXT NOT NULL,
    author       TEXT,
    created_at   TIMESTAMP NOT NULL DEFAULT NOW()
);

--changeset dipol:009-compensations
-- Компенсации подтверждённых расходов сотрудника. В отчётах отделены от ФОТ,
-- даже если физически перечислены одним платежом (US-J4).
CREATE TABLE compensations (
    id          BIGSERIAL PRIMARY KEY,
    employee_id BIGINT NOT NULL REFERENCES employees(id),
    project_id  BIGINT REFERENCES projects(id),
    spent_on    DATE   NOT NULL,
    amount      NUMERIC(12,2) NOT NULL,
    title       TEXT   NOT NULL,            -- «Газ», «Бензин», «Саморезы» …
    material_id BIGINT REFERENCES materials(id),
    status      TEXT NOT NULL DEFAULT 'CHECK_ON_HANDS' CHECK (status IN (
                    'CHECK_ON_HANDS',        -- чек на руках
                    'PASSED_TO_OFFICE',      -- чек передан в офис
                    'PASSED_TO_ACCOUNTANT',  -- чек передан бухгалтеру
                    'COMPENSATED')),         -- компенсировано
    file_id     BIGINT REFERENCES files(id),
    comment     TEXT,
    created_by  TEXT,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_comp_emp ON compensations(employee_id, spent_on DESC);

CREATE TABLE compensation_status_history (
    id               BIGSERIAL PRIMARY KEY,
    compensation_id  BIGINT NOT NULL REFERENCES compensations(id) ON DELETE CASCADE,
    from_status      TEXT,
    to_status        TEXT NOT NULL,
    actor            TEXT,
    created_at       TIMESTAMP NOT NULL DEFAULT NOW()
);

--changeset dipol:009-payouts
-- Выплата сотруднику. Дата выдачи денег и расчётный период — разные вещи (US-L5).
CREATE TABLE payouts (
    id                  BIGSERIAL PRIMARY KEY,
    employee_id         BIGINT NOT NULL REFERENCES employees(id),
    paid_on             DATE   NOT NULL,
    amount              NUMERIC(14,2) NOT NULL,
    channel             TEXT NOT NULL DEFAULT 'CASH' CHECK (channel IN ('OFFICIAL','CASH')),
    period_from         DATE,
    period_to           DATE,
    cash_transaction_id BIGINT,          -- FK ставится в 010
    comment             TEXT,
    created_by          TEXT,
    created_at          TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Платёж распределяется на одно или несколько начислений/периодов; переплата
-- не исчезает, а остаётся отдельным остатком (US-J5).
CREATE TABLE payout_allocations (
    id               BIGSERIAL PRIMARY KEY,
    payout_id        BIGINT NOT NULL REFERENCES payouts(id) ON DELETE CASCADE,
    payroll_entry_id BIGINT REFERENCES payroll_entries(id),
    compensation_id  BIGINT REFERENCES compensations(id),
    adjustment_id    BIGINT REFERENCES payroll_adjustments(id),
    period_month     DATE,
    amount           NUMERIC(14,2) NOT NULL
);

--changeset dipol:009-bridge
-- Отчёт «Мост»: раскладка начисленного ФОТ на официальную и кэш-часть с
-- разнесением по проектам. Правила версионируются; точная формула ждёт
-- отдельного утверждения (Q-11) — здесь хранится её конфигурация и результат.
CREATE TABLE bridge_rules (
    id          BIGSERIAL PRIMARY KEY,
    code        TEXT NOT NULL,
    name        TEXT NOT NULL,
    params_json TEXT NOT NULL,      -- лимиты белой ЗП по сотрудникам и т.п.
    valid_from  DATE NOT NULL,
    valid_to    DATE,
    author      TEXT,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE bridge_reports (
    id           BIGSERIAL PRIMARY KEY,
    period_from  DATE NOT NULL,
    period_to    DATE NOT NULL,
    rule_id      BIGINT REFERENCES bridge_rules(id),
    data_json    TEXT NOT NULL,
    created_by   TEXT,
    created_at   TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE bridge_lines (
    id          BIGSERIAL PRIMARY KEY,
    report_id   BIGINT NOT NULL REFERENCES bridge_reports(id) ON DELETE CASCADE,
    project_id  BIGINT REFERENCES projects(id),
    employee_id BIGINT REFERENCES employees(id),
    accrued     NUMERIC(14,2) NOT NULL DEFAULT 0,
    official    NUMERIC(14,2) NOT NULL DEFAULT 0,
    cash        NUMERIC(14,2) NOT NULL DEFAULT 0,
    correction_official NUMERIC(14,2) NOT NULL DEFAULT 0,
    correction_cash     NUMERIC(14,2) NOT NULL DEFAULT 0,
    materials   NUMERIC(14,2) NOT NULL DEFAULT 0,
    total       NUMERIC(14,2) NOT NULL DEFAULT 0,
    comment     TEXT
);
