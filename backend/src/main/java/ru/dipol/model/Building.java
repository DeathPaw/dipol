package ru.dipol.model;

import java.math.BigDecimal;

/**
 * МКД/объект. Главный ключ — внутренний id; ФИАС и КЛАДР хранятся как внешние
 * идентификаторы, текстовый адрес ключом не является (US-A1, US-A6).
 */
public record Building(
        Long id,
        Integer legacyId,
        String address,
        String addressKey,
        String fiasId,
        String kladr,
        Long zheuId,
        String zheuName,
        Integer floors,
        Integer entrancesCount,
        Integer apartmentsCount,
        Integer apartmentsPerEntrance,
        String roofType,
        String conditionNote,
        String roofAccess,
        String atticAccess,
        String basementAccess,
        String accessNote,
        BigDecimal lat,
        BigDecimal lon,
        boolean isActive,
        Long mergedIntoId
) {}
