--liquibase formatted sql

-- ============================================================================
-- Стартовый перечень проектных конфигураций (раздел 14 бизнес-ТЗ).
-- Конфигурация — набор правил направления; конкретные проекты у клиента
-- создаются поверх неё и заводятся при миграции/из интерфейса.
-- ============================================================================

--changeset dipol:015-configs
INSERT INTO project_configs
    (code, name, description, use_work_objects, default_lifecycle, allowed_pay_schemes, materials_mode, created_by)
VALUES
    ('AB_MAIN', 'Локальный ремонт АБ основной',
     'Абонентский поток локальных ремонтов. Единица заявки — квартира, реже подъезд. В одной заявке допустимо несколько работ.',
     FALSE, 'PERMANENT', 'SHIFT,PIECE_KPI', 'FACT', 'migration'),

    ('AB_EXTRA', 'Локальный ремонт АБ дополнительный',
     'Та же операционная структура, что у АБ основного, но отдельная смета, лимиты и отчётность.',
     FALSE, 'PERMANENT', 'SHIFT,PIECE_KPI', 'FACT', 'migration'),

    ('RK3', 'РК-3: большой ремонт кровель',
     'Планирование крупных кровель: жалобы, дефектовка, план/факт материалов, спецзатраты, вырубка и монтаж.',
     TRUE, 'PERMANENT', 'PIECE,HOUR,SHIFT', 'PLAN_FACT', 'migration'),

    ('SEAMS', 'Межпанельные швы',
     'Погонные метры, этапы согласования объёма (заявлено → перемер → принято), акты подрядчика и заказчика.',
     FALSE, 'PERMANENT', 'PIECE,HOUR', 'FACT', 'migration'),

    ('CANOPY', 'Козырьки',
     'Очистка учитывается в чел·ч, ремонт — в м². Разные SKU не смешиваются.',
     TRUE, 'PERMANENT', 'PIECE,HOUR,SHIFT', 'FACT', 'migration'),

    ('SNOW', 'Снег',
     'Зимний проект с собственным сезоном действия тарифов.',
     FALSE, 'PERMANENT', 'PIECE,HOUR,SHIFT', 'FACT', 'migration'),

    ('ICE', 'Наледь',
     'Зимний проект, отдельный от снега: свои SKU и единицы.',
     FALSE, 'PERMANENT', 'PIECE,HOUR,SHIFT', 'FACT', 'migration'),

    ('CANOPY_DEMOUNT', 'Демонтаж козырьков',
     'Разовое/договорное направление.',
     FALSE, 'ONE_OFF', 'PIECE,SHIFT', 'FACT', 'migration'),

    ('PITCHED', 'Скатные кровли',
     'Специализированные или разовые проекты по скатным кровлям.',
     TRUE, 'ONE_OFF', 'PIECE,HOUR,SHIFT,FIX', 'FACT', 'migration'),

    ('B2C', 'B2C / частные заказы',
     'Частные клиенты и бизнес-клиенты. Как правило разовые проекты с полным циклом: работы, материалы, ФОТ, документы, закрытие.',
     FALSE, 'ONE_OFF', 'PIECE,HOUR,SHIFT,FIX', 'FACT', 'migration');

INSERT INTO project_config_versions (config_id, version, valid_from, author, comment)
SELECT id, 1, CURRENT_DATE, 'migration', 'Стартовая версия правил из бизнес-ТЗ v1.1' FROM project_configs;

--changeset dipol:015-config-statuses
-- Базовая статусная схема доступна всем конфигурациям. Конфигурация может
-- сузить переходы, но значения статусов общие.
INSERT INTO project_config_statuses (config_id, status_code, sort_order)
SELECT c.id, s.code, s.sort_order FROM project_configs c CROSS JOIN request_statuses s;

INSERT INTO project_config_transitions (config_id, from_status, to_status)
SELECT c.id, t.f, t.t FROM project_configs c
CROSS JOIN (VALUES
    ('CREATED','ACCEPTED'), ('CREATED','CANCELLED'),
    ('ACCEPTED','PLANNED'), ('ACCEPTED','IN_PROGRESS'), ('ACCEPTED','CANCELLED'),
    ('PLANNED','IN_PROGRESS'), ('PLANNED','ACCEPTED'), ('PLANNED','CANCELLED'),
    ('IN_PROGRESS','DONE'), ('IN_PROGRESS','PLANNED'), ('IN_PROGRESS','CANCELLED'),
    ('DONE','CUSTOMER_ACCEPTED'), ('DONE','IN_PROGRESS'),
    ('CUSTOMER_ACCEPTED','ACT_CREATED'), ('CUSTOMER_ACCEPTED','DONE'),
    ('ACT_CREATED','ACT_SIGNED'), ('ACT_CREATED','CUSTOMER_ACCEPTED')
) AS t(f, t);

--changeset dipol:015-config-skus
INSERT INTO project_config_skus (config_id, sku_id, is_default, sort_order)
SELECT c.id, s.id, TRUE, s.sort_order
FROM project_configs c JOIN sku s ON TRUE
WHERE (c.code IN ('AB_MAIN','AB_EXTRA') AND s.code LIKE 'AB\_%')
   OR (c.code = 'SEAMS'          AND s.code = 'SV_SEAL')
   OR (c.code = 'CANOPY'         AND s.code IN ('KZ_CLEAN','KZ_REPAIR_ENTRY','KZ_REPAIR_LOGGIA'))
   OR (c.code = 'CANOPY_DEMOUNT' AND s.code = 'KZ_DEMOUNT')
   OR (c.code = 'SNOW'           AND s.code IN ('SN_HORIZ','SN_EDGE'))
   OR (c.code = 'ICE'            AND s.code IN ('SN_EDGE','SN_DRAIN'))
   OR (c.code = 'RK3'            AND s.code IN ('RK3_DEMOLITION','RK3_MOUNT'))
   OR (c.code = 'PITCHED'        AND s.code IN ('PITCHED_ROOF','OTHER'))
   OR (c.code = 'B2C'            AND s.code IN ('SOFT_ROOF','PITCHED_ROOF','OTHER'));

-- «Прочее» доступно везде: настраиваемая позиция вместо создания SKU на лету.
INSERT INTO project_config_skus (config_id, sku_id, is_default, sort_order)
SELECT c.id, s.id, FALSE, 999
FROM project_configs c JOIN sku s ON s.code = 'OTHER'
WHERE NOT EXISTS (
    SELECT 1 FROM project_config_skus x WHERE x.config_id = c.id AND x.sku_id = s.id);

--changeset dipol:015-config-fields
-- Обязательность полей. Адрес, клиент и проект обязательны всегда и заданы
-- NOT NULL в схеме; здесь настраиваются только «мягкие» поля.
INSERT INTO project_config_fields (config_id, field_code, is_visible, is_required)
SELECT c.id, f.code, f.visible, f.required
FROM project_configs c
CROSS JOIN (VALUES
    ('apartment',        TRUE,  FALSE),
    ('entrance',         TRUE,  FALSE),
    ('external_number',  TRUE,  FALSE),
    ('urgency',          TRUE,  TRUE),
    ('description',      TRUE,  TRUE),
    ('responsible',      TRUE,  FALSE),
    ('photo',            TRUE,  FALSE)
) AS f(code, visible, required);

-- Для АБ квартира — фактическая единица заявки, поэтому обязательна.
UPDATE project_config_fields SET is_required = TRUE
WHERE field_code = 'apartment'
  AND config_id IN (SELECT id FROM project_configs WHERE code IN ('AB_MAIN','AB_EXTRA'));

--changeset dipol:015-config-checklist
INSERT INTO project_config_checklist (config_id, item_code, name, is_required, sort_order)
SELECT c.id, i.code, i.name, i.required, i.ord
FROM project_configs c
CROSS JOIN (VALUES
    ('FACT',            'Внесён фактический объём',        TRUE,  10),
    ('PHOTO',           'Загружены фото до/после',         TRUE,  20),
    ('ACT',             'Сформирован акт',                 TRUE,  30),
    ('MESSAGE',         'Отправлено сообщение в MAX',      FALSE, 40),
    ('CUSTOMER_CLOSED', 'Заявка закрыта у заказчика',      TRUE,  50),
    ('ACCEPTED',        'Объём принят мастером/заказчиком',TRUE,  60)
) AS i(code, name, required, ord);
