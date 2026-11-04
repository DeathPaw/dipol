package ru.dipol.model;

import java.time.LocalDate;

/**
 * Конкретный проект у клиента — экземпляр конфигурации (US-O4).
 * Длительный или разовый; разовый обязан иметь критерий завершения.
 */
public record Project(
        Long id,
        String code,
        String name,
        Long clientId,
        String clientName,
        Long configId,
        String configCode,
        String configName,
        int configVersion,
        String lifecycleType,       // PERMANENT | ONE_OFF
        LocalDate startedOn,
        String finishCriteria,
        LocalDate finishedOn,
        String status,              // ACTIVE | SUSPENDED | CLOSED
        Long responsibleEmployeeId,
        String responsibleName,
        LocalDate seasonFrom,
        LocalDate seasonTo,
        String contractNumber,
        String note
) {}
