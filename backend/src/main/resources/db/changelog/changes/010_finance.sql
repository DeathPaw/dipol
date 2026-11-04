--liquibase formatted sql

-- ============================================================================
-- Эпик L: управленческий финансовый контур. Модель «откуда → куда» повторяет
-- действующую книгу «Кассы», но ID операции — стабильный UUID, а не строка,
-- зависящая от номера строки листа (US-L1, миграция P1).
-- ============================================================================

--changeset dipol:010-accounts
-- Контур владения деньгами: «Диполь», «Ковры», «Личное». Нужен, чтобы
-- межбизнесовые взаиморасчёты не растворялись в общей кассе (US-L3).
CREATE TABLE money_circuits (
    code       TEXT PRIMARY KEY,
    name       TEXT NOT NULL,
    sort_order INT  NOT NULL DEFAULT 100
);

CREATE TABLE cash_accounts (
    id           BIGSERIAL PRIMARY KEY,
    name         TEXT NOT NULL,
    account_type TEXT NOT NULL CHECK (account_type IN ('CASH','CARD','BANK','VIRTUAL')),
    circuit_code TEXT NOT NULL REFERENCES money_circuits(code),
    owner        TEXT,
    legal_entity TEXT,
    currency     TEXT NOT NULL DEFAULT 'RUB',
    -- Виртуальные «счета» вроде «Корректировка», «Доставка», «Рабочий»:
    -- в legacy-книге они стоят в колонке «Куда». В остатки не входят.
    is_real      BOOLEAN NOT NULL DEFAULT TRUE,
    is_active    BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order   INT NOT NULL DEFAULT 100,
    created_at   TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE cash_categories (
    code       TEXT PRIMARY KEY,
    name       TEXT NOT NULL,
    direction  TEXT CHECK (direction IN ('INCOME','EXPENSE','BOTH')),
    sort_order INT NOT NULL DEFAULT 100
);

-- Контрагент операции: поставщик, клиент, сотрудник или свободное имя.
CREATE TABLE counterparties (
    id          BIGSERIAL PRIMARY KEY,
    name        TEXT NOT NULL,
    kind        TEXT NOT NULL CHECK (kind IN ('SUPPLIER','CLIENT','EMPLOYEE','OTHER')),
    client_id   BIGINT REFERENCES clients(id),
    employee_id BIGINT REFERENCES employees(id),
    supplier_id BIGINT REFERENCES suppliers(id),
    is_active   BOOLEAN NOT NULL DEFAULT TRUE
);

--changeset dipol:010-transactions
CREATE TABLE cash_transactions (
    id              BIGSERIAL PRIMARY KEY,
    uuid            UUID NOT NULL UNIQUE,     -- неизменяемый ID операции
    legacy_id       TEXT,                     -- «260702-ДИП-РАС-ДИП>РАБ-010000-00»
    occurred_on     DATE NOT NULL,
    direction       TEXT NOT NULL CHECK (direction IN ('INCOME','EXPENSE','TRANSFER')),
    from_account_id BIGINT REFERENCES cash_accounts(id),
    to_account_id   BIGINT REFERENCES cash_accounts(id),
    amount          NUMERIC(14,2) NOT NULL CHECK (amount >= 0),
    category_code   TEXT REFERENCES cash_categories(code),
    counterparty_id BIGINT REFERENCES counterparties(id),
    project_id      BIGINT REFERENCES projects(id),
    circuit_code    TEXT REFERENCES money_circuits(code),
    period_from     DATE,                     -- расчётный период выплаты
    period_to       DATE,
    status          TEXT NOT NULL DEFAULT 'POSTED'
                    CHECK (status IN ('DRAFT','POSTED','REVERSED')),
    comment         TEXT,
    -- Удаление проведённой операции запрещено: только сторно (US-L6).
    reverses_id     BIGINT REFERENCES cash_transactions(id),
    created_by      TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_cashtx_date ON cash_transactions(occurred_on DESC);
CREATE INDEX idx_cashtx_from ON cash_transactions(from_account_id);
CREATE INDEX idx_cashtx_to ON cash_transactions(to_account_id);
CREATE INDEX idx_cashtx_project ON cash_transactions(project_id);

ALTER TABLE payouts ADD CONSTRAINT fk_payout_tx
    FOREIGN KEY (cash_transaction_id) REFERENCES cash_transactions(id);

-- Связь платежа с основанием: начисление, компенсация, закупка (US-L4).
-- Один расход может закрывать несколько обязательств частично.
CREATE TABLE transaction_allocations (
    id               BIGSERIAL PRIMARY KEY,
    transaction_id   BIGINT NOT NULL REFERENCES cash_transactions(id) ON DELETE CASCADE,
    basis_type       TEXT NOT NULL CHECK (basis_type IN
                        ('PAYROLL','COMPENSATION','PURCHASE','EQUIPMENT_SHIFT','VEHICLE','OTHER')),
    payroll_entry_id BIGINT REFERENCES payroll_entries(id),
    compensation_id  BIGINT REFERENCES compensations(id),
    purchase_order_id BIGINT REFERENCES purchase_orders(id),
    equipment_shift_id BIGINT REFERENCES equipment_shifts(id),
    amount           NUMERIC(14,2) NOT NULL,
    comment          TEXT
);
CREATE INDEX idx_txalloc_tx ON transaction_allocations(transaction_id);

-- Взаимные обязательства между контурами: «Диполь должен Коврам» и т.п.
CREATE TABLE circuit_debts (
    id             BIGSERIAL PRIMARY KEY,
    debtor_code    TEXT NOT NULL REFERENCES money_circuits(code),
    creditor_code  TEXT NOT NULL REFERENCES money_circuits(code),
    transaction_id BIGINT REFERENCES cash_transactions(id),
    amount         NUMERIC(14,2) NOT NULL,
    settled_at     DATE,
    settled_by_tx  BIGINT REFERENCES cash_transactions(id),
    comment        TEXT,
    created_at     TIMESTAMP NOT NULL DEFAULT NOW()
);
