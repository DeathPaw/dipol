package ru.dipol.model;

import java.time.LocalDateTime;

/**
 * Пользователь ERP. Не путать с {@link Employee}: сотрудник выполняет работы,
 * пользователь входит в интерфейс. Один человек может быть и тем и другим —
 * связь через {@code employeeId}.
 */
public record AppUser(
        Long id,
        String username,
        String passwordHash,
        String displayName,
        String role,          // SUPERVISOR | ADMIN | OPERATOR | FOREMAN | WORKER | SAFETY | READONLY
        Long employeeId,
        boolean isActive,
        String pinCode,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
