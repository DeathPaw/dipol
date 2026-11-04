package ru.dipol.model;

/** Контактное лицо клиента с ролью: кому звонить по производству, актам, оплате (US-O2). */
public record ClientContact(
        Long id,
        Long clientId,
        String fullName,
        String position,
        String contactRole,
        String phone,
        String email,
        String messenger,
        Long zheuId,
        String comment,
        boolean isActive
) {}
