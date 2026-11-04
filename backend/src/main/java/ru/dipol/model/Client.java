package ru.dipol.model;

/** Клиент: юрлицо, ИП или физлицо в едином реестре (US-O1). */
public record Client(
        Long id,
        String clientType,      // LEGAL | IP | PERSON
        String name,
        String shortName,
        String inn,
        String kpp,
        String ogrn,
        String legalAddress,
        String actualAddress,
        String bankName,
        String bankBik,
        String bankAccount,
        String corrAccount,
        String phone,
        String email,
        String comment,
        boolean isActive,
        Long mergedIntoId
) {}
