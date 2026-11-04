package ru.dipol.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Позиция заявки. Этапы объёма хранятся раздельно: заявлено подрядчиком →
 * выполнено → перемер → финально принято (US-G3). Разные физические единицы
 * никогда не складываются в один «общий объём».
 */
public record RequestItem(
        Long id,
        Long requestId,
        Long workObjectId,
        String workObjectName,
        Long skuId,
        String skuCode,
        String skuName,
        String unitCode,
        BigDecimal plannedQty,
        BigDecimal declaredQty,
        BigDecimal factQty,
        BigDecimal remeasuredQty,
        BigDecimal acceptedQty,
        BigDecimal manHours,
        BigDecimal referenceQty,
        String referenceUnitCode,
        String repairType,
        String status,
        String acceptedBy,
        LocalDateTime acceptedAt,
        String acceptComment,
        String note,
        int sortOrder,
        List<Long> assigneeIds
) {}
