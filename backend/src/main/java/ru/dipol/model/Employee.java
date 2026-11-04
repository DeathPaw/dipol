package ru.dipol.model;

import java.time.LocalDate;
import java.util.List;

/** Сотрудник. Жёсткой сущности «бригада» нет: состав фиксируется людьми (US-A5). */
public record Employee(
        Long id,
        String fullName,
        String shortName,
        String roleCode,
        String roleName,
        String position,
        String phone,
        String email,
        LocalDate hiredOn,
        LocalDate firedOn,
        boolean isActive,
        String note,
        List<Long> skuIds
) {}
