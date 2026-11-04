package ru.dipol.model;

import java.util.List;

/**
 * Проект-конфигурация: переиспользуемый версионируемый набор правил
 * направления (US-A3). Это не проект у заказчика — тот создаётся поверх неё.
 */
public record ProjectConfig(
        Long id,
        String code,
        String name,
        String description,
        boolean useWorkObjects,
        String defaultLifecycle,       // PERMANENT | ONE_OFF
        List<String> allowedPaySchemes,
        String materialsMode,          // FACT | PLAN_FACT
        int currentVersion,
        boolean isActive,
        int projectsCount
) {}
