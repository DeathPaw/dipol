--liquibase formatted sql

-- ============================================================================
-- Справочники со стартовым наполнением. Всё, что здесь лежит, редактируется
-- администратором из интерфейса — это данные, а не константы кода.
-- ============================================================================

--changeset dipol:014-units
INSERT INTO units (code, name, kind, sort_order) VALUES
    ('M2',    'м²',      'AREA',     10),
    ('M3',    'м³',      'VOLUME',   20),
    ('MP',    'м.пог.',  'LENGTH',   30),
    ('PCS',   'шт.',     'COUNT',    40),
    ('MH',    'чел·ч',   'TIME',     50),
    ('HOUR',  'ч',       'TIME',     55),
    ('SHIFT', 'смена',   'COUNT',    60),
    ('RUN',   'рейс',    'COUNT',    65),
    ('KM',    'км',      'DISTANCE', 70),
    ('ROLL',  'рулон',   'COUNT',    80),
    ('KG',    'кг',      'COUNT',    85),
    ('L',     'л',       'VOLUME',   86),
    ('SET',   'к-т',     'COUNT',    90);

--changeset dipol:014-request-statuses
INSERT INTO request_statuses (code, name, sort_order, is_terminal, color) VALUES
    ('CREATED',        'Создана',            10, FALSE, '#eaf4fd'),
    ('ACCEPTED',       'Принята в работу',   20, FALSE, '#d5e8d4'),
    ('PLANNED',        'Запланирована',      30, FALSE, '#dae8fc'),
    ('IN_PROGRESS',    'В работе',           40, FALSE, '#fff3cd'),
    ('DONE',           'Выполнена',          50, FALSE, '#d4edda'),
    ('CUSTOMER_ACCEPTED','Принята заказчиком',60, FALSE, '#cce5ff'),
    ('ACT_CREATED',    'Акт создан',         70, FALSE, '#e8d5f0'),
    ('ACT_SIGNED',     'Акт подписан',       80, TRUE,  '#2c3e50'),
    ('CANCELLED',      'Отменена',           90, TRUE,  '#f8d7da');

--changeset dipol:014-request-dicts
INSERT INTO request_sources (code, name, sort_order) VALUES
    ('CUSTOMER_SYSTEM', 'Система заказчика', 10),
    ('SUPER_MKD',       'СУПЕР МКД+',        20),
    ('VERBAL',          'Устно',             30),
    ('PAPER',           'Бумага',            40),
    ('MASTER',          'Мастер',            50),
    ('CLIENT',          'Заказчик',          60),
    ('OTHER',           'Другое',            70);

INSERT INTO financial_classes (code, name, sort_order) VALUES
    ('AB_CONTRACT', 'Работа по договору АБ', 10),
    ('ABOVE_AB',    'Сверх АБ',              20),
    ('AB_EXTRA',    'АБ дополнительный',     30),
    ('WARRANTY',    'Гарантия',              40),
    ('B2C',         'Частный заказ (B2C)',   50),
    ('OTHER',       'Другое',                60);

INSERT INTO wait_reasons (code, name, sort_order) VALUES
    ('WEATHER',    'Погода',            10),
    ('PREPAYMENT', 'Аванс',             20),
    ('MATERIAL',   'Материал',          30),
    ('ACCESS',     'Нет доступа',       40),
    ('APPROVAL',   'Согласование',      50),
    ('EQUIPMENT',  'Спецтехника',       60),
    ('OTHER',      'Другое',            70);

INSERT INTO reschedule_reasons (code, name, sort_order) VALUES
    ('WEATHER',   'Погода',       10),
    ('NO_ACCESS', 'Нет доступа',  20),
    ('MATERIAL',  'Материал',     30),
    ('RESOURCE',  'Нет ресурса',  40),
    ('CUSTOMER',  'Заказчик',     50),
    ('CONTRACTOR','Исполнитель',  60),
    ('OTHER',     'Другое',       70);

INSERT INTO problem_categories (code, name, sort_order) VALUES
    ('ACCESS',    'Нет доступа на объект',   10),
    ('MATERIAL',  'Нет материала',           20),
    ('EQUIPMENT', 'Нет спецтехники',         30),
    ('WEATHER',   'Погодные условия',        40),
    ('DEFECT',    'Скрытый дефект объекта',  50),
    ('SAFETY',    'Нарушение требований ТБ', 60),
    ('OTHER',     'Другое',                  70);

--changeset dipol:014-employee-roles
INSERT INTO employee_roles (code, name, sort_order) VALUES
    ('FOREMAN',     'Прораб/мастер',       10),
    ('ROOFER',      'Кровельщик',          20),
    ('ROPE_ACCESS', 'Промышленный альпинист', 30),
    ('HELPER',      'Помощник/разнорабочий',  40),
    ('DRIVER',      'Водитель',            50),
    ('SUPPLY',      'Снабжение',           60),
    ('SAFETY',      'Техник по безопасности', 70),
    ('OFFICE',      'Офис',                80);

--changeset dipol:014-sku
INSERT INTO sku_groups (id, name, sort_order) VALUES
    (1, 'Локальный ремонт кровли', 10),
    (2, 'Межпанельные швы',        20),
    (3, 'Козырьки',                30),
    (4, 'Снег и наледь',           40),
    (5, 'Большой ремонт кровли (РК-3)', 50),
    (6, 'Прочее',                  90);
SELECT setval(pg_get_serial_sequence('sku_groups','id'), 6);

INSERT INTO sku (code, name, group_id, unit_code, act_wording, sort_order) VALUES
    -- Локальный ремонт (АБ / АБ-доп). Названия — как в действующих листах.
    ('AB_ADJ_VENT',    'Примыкание к вентканалу, лазу или парапету', 1, 'M2', 'Локальный ремонт кровли', 10),
    ('AB_ADJ_DRAIN',   'Ремонт примыкания кровли к внутреннему ливнестоку', 1, 'M2', 'Локальный ремонт кровли', 20),
    ('AB_PATCH',       'Заплатка горизонтальная', 1, 'M2', 'Локальный ремонт кровли', 30),
    ('AB_ADJ_SEWER',   'Ремонт примыкания кровли к канализационному стояку', 1, 'M2', 'Локальный ремонт кровли', 40),
    ('AB_ADJ_PANEL',   'Ремонт примыкания кровли к выходу электрощита', 1, 'M2', 'Локальный ремонт кровли', 50),
    ('AB_ADJ_STACK',   'Ремонт примыкания кровли к вентиляционному стояку', 1, 'M2', 'Локальный ремонт кровли', 55),
    ('AB_WASTE',       'Уборка и вывоз мусора', 1, 'M3', 'Уборка и вывоз мусора', 60),
    ('AB_DEFECT',      'Дефектовка', 1, 'PCS', 'Дефектовка кровли', 70),
    -- Межпанельные швы
    ('SV_SEAL',        'Герметизация межпанельных швов', 2, 'MP', 'Герметизация межпанельных швов', 100),
    -- Козырьки. Очистка учитывается в чел·ч, ремонт — в м²; единицы не смешиваются.
    ('KZ_CLEAN',       'Очистка козырька над входной группой', 3, 'MH', 'Очистка козырька', 200),
    ('KZ_REPAIR_ENTRY','Ремонт кровельного покрытия козырька над входной группой', 3, 'M2', 'Ремонт козырька', 210),
    ('KZ_REPAIR_LOGGIA','Ремонт кровельного покрытия козырька лоджии', 3, 'M2', 'Ремонт козырька лоджии', 220),
    ('KZ_DEMOUNT',     'Демонтаж козырька', 3, 'PCS', 'Демонтаж козырька', 230),
    -- Снег и наледь: три разные единицы, в «общий объём» не складываются.
    ('SN_EDGE',        'Очистка снега и наледи по кромке кровли', 4, 'MP', 'Очистка снега и наледи', 300),
    ('SN_HORIZ',       'Очистка снега с горизонтальной поверхности', 4, 'M2', 'Очистка снега', 310),
    ('SN_DRAIN',       'Прочистка наружного ливнестока хим. солью', 4, 'PCS', 'Прочистка ливнестока', 320),
    -- РК-3
    ('RK3_DEMOLITION', 'Демонтаж (вырубка) кровли', 5, 'M2', 'Демонтаж кровельного покрытия', 400),
    ('RK3_MOUNT',      'Монтаж кровельного покрытия', 5, 'M2', 'Монтаж кровельного покрытия', 410),
    -- Прочее и частные заказы
    ('SOFT_ROOF',      'Устройство мягкой кровли', 6, 'M2', 'Устройство мягкой кровли', 900),
    ('PITCHED_ROOF',   'Работы по скатной кровле', 6, 'M2', 'Работы по скатной кровле', 910),
    ('OTHER',          'Прочее', 6, 'PCS', 'Прочие работы', 990);

--changeset dipol:014-permissions
INSERT INTO permissions (code, name, section) VALUES
    ('REQUEST_VIEW',     'Просмотр заявок',              'Заявки'),
    ('REQUEST_EDIT',     'Создание и изменение заявок',  'Заявки'),
    ('REQUEST_STATUS_OVERRIDE', 'Обход правил перехода статуса', 'Заявки'),
    ('PLANNING_EDIT',    'Планирование выездов',         'Планирование'),
    ('FACT_ACCEPT',      'Приёмка факта работ',          'Исполнение'),
    ('MATERIAL_ISSUE',   'Списание материалов',          'Материалы'),
    ('MATERIAL_EDIT',    'Закупки и поступления',        'Материалы'),
    ('PAYROLL_VIEW',     'Просмотр ФОТ',                 'ФОТ'),
    ('PAYROLL_EDIT',     'Начисления и условия оплаты',  'ФОТ'),
    ('FINANCE_VIEW',     'Просмотр касс',                'Финансы'),
    ('FINANCE_EDIT',     'Кассовые операции',            'Финансы'),
    ('FINANCE_REVERSE',  'Сторно кассовых операций',     'Финансы'),
    ('PERIOD_CLOSE',     'Закрытие периода',             'Закрытие'),
    ('REFERENCE_EDIT',   'Настройка справочников',       'Администрирование'),
    ('CONFIG_EDIT',      'Проектные конфигурации',       'Администрирование'),
    ('USER_EDIT',        'Управление пользователями',    'Администрирование'),
    ('SAFETY_EDIT',      'Раздел ТБ',                    'ТБ'),
    ('TRANSPORT_EDIT',   'Транспорт и оборудование',     'Транспорт'),
    ('COST_VIEW',        'Производственная себестоимость','Аналитика'),
    ('IMPORT_RUN',       'Импорт и миграция',            'Администрирование');

INSERT INTO role_permissions (role, permission_code)
SELECT 'SUPERVISOR', code FROM permissions;

INSERT INTO role_permissions (role, permission_code) VALUES
    ('ADMIN','REQUEST_VIEW'),('ADMIN','REQUEST_EDIT'),('ADMIN','PLANNING_EDIT'),
    ('ADMIN','FACT_ACCEPT'),('ADMIN','MATERIAL_EDIT'),('ADMIN','MATERIAL_ISSUE'),
    ('ADMIN','REFERENCE_EDIT'),('ADMIN','CONFIG_EDIT'),('ADMIN','USER_EDIT'),
    ('ADMIN','SAFETY_EDIT'),('ADMIN','TRANSPORT_EDIT'),('ADMIN','IMPORT_RUN'),

    ('OPERATOR','REQUEST_VIEW'),('OPERATOR','REQUEST_EDIT'),('OPERATOR','MATERIAL_EDIT'),
    ('OPERATOR','PLANNING_EDIT'),

    ('FOREMAN','REQUEST_VIEW'),('FOREMAN','REQUEST_EDIT'),('FOREMAN','PLANNING_EDIT'),
    ('FOREMAN','FACT_ACCEPT'),('FOREMAN','MATERIAL_ISSUE'),('FOREMAN','TRANSPORT_EDIT'),

    -- Рабочий на старте материалы не списывает (US-N1).
    ('WORKER','REQUEST_VIEW'),

    ('SAFETY','SAFETY_EDIT'),('SAFETY','REQUEST_VIEW'),

    ('READONLY','REQUEST_VIEW');

--changeset dipol:014-finance-dicts
INSERT INTO money_circuits (code, name, sort_order) VALUES
    ('DIPOL',   'Диполь',  10),
    ('CARPETS', 'Ковры',   20),
    ('PERSONAL','Личное',  30),
    ('OTHER',   'Прочее',  40);

INSERT INTO cash_categories (code, name, direction, sort_order) VALUES
    ('SALARY',        'Зарплата',            'EXPENSE', 10),
    ('COMPENSATION',  'Компенсация',         'EXPENSE', 20),
    ('MATERIALS',     'Материалы',           'EXPENSE', 30),
    ('TRANSPORT',     'Транспорт',           'EXPENSE', 40),
    ('EQUIPMENT',     'Спецтехника',         'EXPENSE', 50),
    ('TAXES',         'Налоги',              'EXPENSE', 60),
    ('OTHER_EXPENSE', 'Прочее',              'EXPENSE', 70),
    ('CLIENT_PAYMENT','Оплата клиента',      'INCOME',  80),
    ('OFFICE_MONEY',  'Деньги от конторы',   'INCOME',  90),
    ('OPENING',       'Начальный остаток',   'BOTH',   100),
    ('CORRECTION',    'Корректировка',       'BOTH',   110),
    ('SELF_COMP',     'Компенсация от себя', 'BOTH',   120);

INSERT INTO vehicle_expense_categories (code, name, sort_order) VALUES
    ('FUEL','Топливо',10),('REPAIR','Ремонт',20),('SERVICE','ТО',30),
    ('PARTS','Детали',40),('WASH','Мойка',50),('INSURANCE','Страховка',60),
    ('EQUIPMENT','Оснащение',70),('OTHER','Прочее',80);

INSERT INTO special_equipment_types (code, name, default_resource_unit, sort_order) VALUES
    ('PUHTO',      'ПУХТО',        'RUN',   10),
    ('CRANE',      'Кран',         'HOUR',  20),
    ('LIFT',       'Вышка',        'HOUR',  30),
    ('MANIPULATOR','Манипулятор',  'HOUR',  40),
    ('TRUCK',      'Грузовик',     'RUN',   50),
    ('OTHER',      'Прочее',       'SHIFT', 60);

--changeset dipol:014-pay-schemes
INSERT INTO pay_scheme_types (code, name, basis_unit, sort_order) VALUES
    ('SHIFT',     'За смену',            'SHIFT', 10),
    ('HOUR',      'Почасовая',           'MH',    20),
    ('PIECE',     'Сдельная по объёму',  NULL,    30),
    ('PIECE_KPI', 'Сдельная/KPI',        NULL,    40),
    ('FIX',       'Фиксированная',       NULL,    50),
    ('MIXED',     'Комбинированная',     NULL,    60);

--changeset dipol:014-safety-dicts
INSERT INTO safety_doc_types (code, name, sort_order) VALUES
    ('ORDER',       'Приказ',                10),
    ('INSTRUCTION', 'Инструкция',            20),
    ('FORM',        'Форма/бланк',           30),
    ('PROTOCOL',    'Протокол проверки знаний', 40),
    ('CERT',        'Удостоверение',         50),
    ('SCAN',        'Скан документа',        60),
    ('OTHER',       'Прочее',                70);

-- Стартовый перечень журналов. Точный список утверждается со специалистом ТБ (Q-10).
INSERT INTO safety_journals (code, name) VALUES
    ('INTRO',     'Журнал вводного инструктажа'),
    ('WORKPLACE', 'Журнал инструктажа на рабочем месте'),
    ('PERMITS',   'Журнал учёта нарядов-допусков'),
    ('EQUIPMENT', 'Журнал учёта и осмотра СИЗ и оборудования');

--changeset dipol:014-parameters
INSERT INTO parameters (code, name, unit_code, value_type, description) VALUES
    ('AB_LIMIT_M2',        'Ориентировочный лимит локремонта', 'M2', 'NUMBER',
     'Информационное предупреждение, работу не блокирует (US-E2)'),
    ('RK3_AREA_PER_ENTRANCE','Норматив площади кровли на подъезд', 'M2', 'NUMBER',
     'Требует подтверждения владельцем процесса — Q-02'),
    ('RK3_DEMOLITION_WIDTH','Норматив ширины выруба', 'MP', 'NUMBER',
     'Требует подтверждения владельцем процесса — Q-02'),
    ('RK3_BASE_RATE',      'Базовая ставка вырубки за чел·ч', NULL, 'NUMBER',
     'Конфликт 595 / 610 в legacy — до утверждения расчёт не запускается (Q-03)'),
    ('RK3_COMPLAINT_THRESHOLD','Порог количества жалоб для включения в очередь РК-3', NULL, 'NUMBER',
     'Требует подтверждения — Q-02'),
    ('WHITE_SALARY_LIMIT', 'Лимит официальной части ЗП в месяц', NULL, 'NUMBER',
     'Используется отчётом «Мост» — формула ждёт утверждения (Q-11)');

-- Значения — как найдено в действующих таблицах. Дата действия открытая,
-- пересмотр = новая версия, закрытые расчёты не пересчитываются.
INSERT INTO parameter_versions (parameter_id, value_num, valid_from, author, comment)
SELECT id, 10,     DATE '2024-01-01', 'migration', 'Из общего контекста: лимит ≤10 м²' FROM parameters WHERE code='AB_LIMIT_M2'
UNION ALL SELECT id, 150, DATE '2024-01-01', 'migration', 'Из формул листа ОСН — требует подтверждения' FROM parameters WHERE code='RK3_AREA_PER_ENTRANCE'
UNION ALL SELECT id, 15,  DATE '2024-01-01', 'migration', 'Из формул листа ОСН — требует подтверждения' FROM parameters WHERE code='RK3_DEMOLITION_WIDTH'
UNION ALL SELECT id, 100000, DATE '2026-01-01', 'migration', 'Лимит белой ЗП из листа ФОТ_Нормализация' FROM parameters WHERE code='WHITE_SALARY_LIMIT';

--changeset dipol:014-rk3-coefficients
-- Диапазоны k для вырубки РК-3. Взяты из брифа, границы записаны явно.
-- Пока не подтверждены владельцем процесса (Q-03), расчёт помечен как черновой.
INSERT INTO coefficient_ranges (code, from_value, from_inclusive, to_value, to_inclusive, k, valid_from, author, comment) VALUES
    ('RK3_DEMOLITION_K', NULL, TRUE,  1.10, FALSE, 1.00, DATE '2025-01-01', 'migration', 'p < 1,10 — требует подтверждения (Q-03)'),
    ('RK3_DEMOLITION_K', 1.10, TRUE,  1.25, FALSE, 1.25, DATE '2025-01-01', 'migration', '1,10 ≤ p < 1,25 — из брифа по вырубке'),
    ('RK3_DEMOLITION_K', 1.25, TRUE,  1.40, FALSE, 1.40, DATE '2025-01-01', 'migration', '1,25 ≤ p < 1,40 — из брифа по вырубке'),
    ('RK3_DEMOLITION_K', 1.40, TRUE,  NULL, FALSE, 1.60, DATE '2025-01-01', 'migration', 'p ≥ 1,40 — требует подтверждения (Q-03)');
