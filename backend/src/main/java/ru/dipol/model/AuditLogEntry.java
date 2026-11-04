package ru.dipol.model;

import java.time.LocalDateTime;

/**
 * Запись журнала действий. Пользователем не редактируется: UPDATE/DELETE
 * по этой таблице в коде отсутствуют (US-N2).
 */
public record AuditLogEntry(
        Long id,
        String entityType,
        Long entityId,
        String action,
        String field,
        String oldValue,
        String newValue,
        String comment,
        String actor,
        LocalDateTime createdAt
) {}
