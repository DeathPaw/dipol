--liquibase formatted sql

-- ============================================================================
-- Эпик F: РК-3. Домовая очередь по жалобам, дефектовка, снабжение и
-- спецзатраты адреса, принятый факт вырубки/монтажа и расчёт ФОТ вырубки.
--
-- P0 из ТЗ: legacy-формула выплаты использует p вместо k. Здесь выплата
-- считается ТОЛЬКО как часы × базовая ставка × k, где k берётся из
-- версионируемой таблицы coefficient_ranges. Перенос ошибочной формулы
-- как эталона не предусмотрен конструкцией таблицы.
-- ============================================================================

--changeset dipol:013-defect-survey
CREATE TABLE defect_surveys (
    id            BIGSERIAL PRIMARY KEY,
    building_id   BIGINT NOT NULL REFERENCES buildings(id),
    project_id    BIGINT REFERENCES projects(id),
    survey_date   DATE   NOT NULL,
    author_employee_id BIGINT REFERENCES employees(id),
    description   TEXT,
    entrances     TEXT,                    -- обследованные подъезды
    estimated_roof_m2  NUMERIC(12,2),
    estimated_demolition_m2 NUMERIC(12,2),
    -- Расчёт показывает формулу и допускает ручную корректировку с причиной.
    calc_formula  TEXT,
    manual_override_reason TEXT,
    status        TEXT NOT NULL DEFAULT 'DRAFT'
                  CHECK (status IN ('DRAFT','DONE','APPROVED','REJECTED')),
    created_at    TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_defect_building ON defect_surveys(building_id, survey_date DESC);

--changeset dipol:013-queue
-- Снимок домовой очереди РК-3 (US-F1, US-M3). Формульная рекомендация
-- подъездов хранится отдельно от утверждённого решения.
CREATE TABLE rk3_queue (
    id                   BIGSERIAL PRIMARY KEY,
    building_id          BIGINT NOT NULL REFERENCES buildings(id),
    project_id           BIGINT REFERENCES projects(id),
    season_year          INT,
    entrances_count      INT,
    requests_total       INT,
    requests_active      INT,
    requests_per_entrance TEXT,           -- распределение заявок по подъездам
    repeat_apartments    TEXT,
    trips_count          INT,
    roof_type            TEXT,
    suggested_entrances  TEXT,            -- что предложил алгоритм
    approved_entrances   TEXT,            -- что утвердил руководитель
    with_demolition      BOOLEAN NOT NULL DEFAULT FALSE,
    demolition_width_m   NUMERIC(8,2),
    estimated_roof_m2    NUMERIC(12,2),
    estimated_demolition_m2 NUMERIC(12,2),
    status               TEXT,             -- Дефектовка / В плане / Выполнено
    priority             INT NOT NULL DEFAULT 0,
    priority_reason      TEXT,
    algorithm_version    TEXT,             -- версия параметров расчёта
    calculated_at        TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (building_id, season_year)
);

--changeset dipol:013-objects
-- Объект РК-3 = адрес в работе большого ремонта. Заменяет строку листа РК3.
CREATE TABLE rk3_objects (
    id                 BIGSERIAL PRIMARY KEY,
    act_number         TEXT,                    -- РК2_2025_1 …
    project_id         BIGINT NOT NULL REFERENCES projects(id),
    building_id        BIGINT NOT NULL REFERENCES buildings(id),
    seq_in_year        INT,
    season_year        INT,
    defect_survey_id   BIGINT REFERENCES defect_surveys(id),
    defect_date        DATE,

    shipment_date      DATE,
    supplier_order_number TEXT,
    delivery_cost      NUMERIC(12,2),
    unload_cost        NUMERIC(12,2),
    lift_method        TEXT,                    -- кран, вышка заказчика, вручную
    lift_cost          NUMERIC(12,2),
    waste_cost         NUMERIC(12,2),           -- ПУХТО/вывоз мусора
    shipment_total     NUMERIC(14,2),

    crew_roofers       TEXT,
    crew_demolition    TEXT,
    accepted_at        DATE,                    -- дата приёмки заказчиком

    -- Принятый факт вырубки отделён от фактических человеко-часов (US-F5).
    accepted_demolition_m2 NUMERIC(12,2),
    fact_demolition_man_hours NUMERIC(12,2),

    -- Расчёт: p = принятые м² / чел·ч; k — из версионируемых диапазонов;
    -- выплата = часы × базовая ставка × k. Формула p×ставка запрещена.
    productivity       NUMERIC(12,4),
    coefficient_k      NUMERIC(12,4),
    coefficient_range_id BIGINT REFERENCES coefficient_ranges(id),
    base_rate_demolition NUMERIC(12,2),
    rate_roof          NUMERIC(12,2),
    payroll_demolition NUMERIC(14,2),
    payroll_roof       NUMERIC(14,2),
    payroll_total      NUMERIC(14,2),
    calc_formula       TEXT,

    customer_rep       TEXT,
    contractor_rep     TEXT,
    act_file_id        BIGINT REFERENCES files(id),
    defect_act_file_id BIGINT REFERENCES files(id),
    status             TEXT NOT NULL DEFAULT 'PLANNED'
                       CHECK (status IN ('PLANNED','SUPPLIED','IN_PROGRESS','ACCEPTED','CLOSED','CANCELLED')),
    note               TEXT,
    created_by         TEXT,
    created_at         TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_rk3_building ON rk3_objects(building_id);
CREATE INDEX idx_rk3_project ON rk3_objects(project_id, season_year);

ALTER TABLE payroll_entries ADD CONSTRAINT fk_payroll_rk3
    FOREIGN KEY (rk3_object_id) REFERENCES rk3_objects(id);

-- Строки объёмов: демонтаж и монтаж по типам материала/слоя, план и факт.
CREATE TABLE rk3_object_lines (
    id            BIGSERIAL PRIMARY KEY,
    rk3_object_id BIGINT NOT NULL REFERENCES rk3_objects(id) ON DELETE CASCADE,
    line_kind     TEXT NOT NULL CHECK (line_kind IN ('DEMOLITION','MOUNT')),
    material_id   BIGINT REFERENCES materials(id),
    layer_name    TEXT,                    -- «Линокром ТКП», «Унифлекс ЭКП» …
    planned_qty   NUMERIC(12,2),
    accepted_qty  NUMERIC(12,2),
    unit_code     TEXT NOT NULL REFERENCES units(code),
    rolls_qty     NUMERIC(12,2),
    price_per_roll NUMERIC(12,2),
    amount        NUMERIC(14,2),
    sort_order    INT NOT NULL DEFAULT 100
);
CREATE INDEX idx_rk3lines_obj ON rk3_object_lines(rk3_object_id);

-- Корректировка принятого объёма после закрытия — новой записью, не правкой.
CREATE TABLE rk3_corrections (
    id            BIGSERIAL PRIMARY KEY,
    rk3_object_id BIGINT NOT NULL REFERENCES rk3_objects(id) ON DELETE CASCADE,
    line_id       BIGINT REFERENCES rk3_object_lines(id),
    field         TEXT NOT NULL,
    old_value     TEXT,
    new_value     TEXT,
    reason        TEXT NOT NULL,
    author        TEXT,
    created_at    TIMESTAMP NOT NULL DEFAULT NOW()
);
