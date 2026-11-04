package ru.dipol.model;

import java.time.LocalDateTime;

/** Запомненное написание адреса из внешней системы. */
public record BuildingAlias(
        Long id,
        Long buildingId,
        String alias,
        String aliasKey,
        String source,
        String createdBy,
        LocalDateTime createdAt
) {}
