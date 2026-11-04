package ru.dipol.model;

import java.time.LocalDateTime;

/** Пункт чек-листа закрытия заявки (US-K5). */
public record ChecklistItem(
        Long id,
        Long requestId,
        String itemCode,
        String name,
        boolean isRequired,
        boolean isDone,
        LocalDateTime doneAt,
        String doneBy,
        String skippedReason
) {}
