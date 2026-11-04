package ru.dipol.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Заявка — основная операционная единица: одна проблема на одном адресе.
 *
 * <p>Проект, финансовый класс, статус выполнения и закрытие у заказчика —
 * четыре независимых поля (US-B3). В действующих таблицах они были смешаны
 * в одну колонку «Статус», из-за чего отчёты не разделялись.
 */
public record Request(
        Long id,
        String number,
        Integer seqInProject,
        String externalNumber,
        String externalSystem,
        String sourceCode,

        Long clientId,
        String clientName,
        Long projectId,
        String projectCode,
        String projectName,
        Long configId,
        Long buildingId,
        String buildingAddress,
        Integer buildingLegacyId,
        Long zheuId,
        String zheuName,

        String apartment,
        Integer entrance,
        boolean entranceIsManual,
        Integer entranceAuto,
        String entranceFixReason,
        String placeNote,

        LocalDateTime receivedAtCustomer,
        LocalDateTime receivedAt,
        LocalDate plannedDate,
        LocalDateTime doneAt,
        LocalDate customerAcceptedAt,

        String urgency,
        boolean isWarranty,
        Long parentRequestId,
        String linkType,
        String linkReason,

        String status,
        String statusName,
        String financialClass,
        boolean customerClosed,
        boolean isClosed,
        LocalDateTime closedAt,
        String cancelReason,

        int priority,
        String priorityReason,

        String description,
        String actWording,
        String internalComment,

        Long responsibleEmployeeId,
        String responsibleName,
        Long contactId,
        String contactAdhocName,
        String contactAdhocPhone,

        String reportLink,
        String photoLink,
        String reportText,

        String createdBy,
        LocalDateTime createdAt
) {}
