package ru.dipol.model;

import java.time.LocalDateTime;

/** Событие ленты заявки. История не перезаписывается при правке полей (US-B6). */
public record RequestEvent(
        Long id,
        Long requestId,
        String eventType,
        String text,
        String waitReason,
        String payloadJson,
        String actor,
        LocalDateTime createdAt
) {}
