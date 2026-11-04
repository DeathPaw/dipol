package ru.dipol.model;

/**
 * Работа/услуга. Расчёты и тарифы ссылаются на id, а не на отображаемое
 * название: одинаковые названия с разными ставками — источник ошибок в
 * действующих таблицах (US-A4).
 */
public record Sku(
        Long id,
        String code,
        String name,
        Long groupId,
        String groupName,
        String unitCode,
        String actWording,
        boolean requiresPhoto,
        boolean isActive,
        String description,
        int sortOrder
) {}
