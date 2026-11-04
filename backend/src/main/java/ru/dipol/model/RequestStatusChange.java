package ru.dipol.model;

import java.time.LocalDateTime;

public record RequestStatusChange(
        Long id,
        Long requestId,
        String fromStatus,
        String toStatus,
        String reason,
        boolean isOverride,
        String actor,
        LocalDateTime createdAt
) {}
