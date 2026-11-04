package ru.dipol.model;

import java.time.LocalDateTime;

public record ErrorLogEntry(
        Long id,
        String errorType,
        String message,
        String requestPath,
        String actor,
        LocalDateTime createdAt
) {}
