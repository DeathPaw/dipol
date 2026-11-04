package ru.dipol.model;

/** ЖЭУ: контакты и реквизиты подставляются в заявки, акты и сообщения. */
public record Zheu(
        Long id,
        Integer legacyId,
        String name,
        String headName,
        String address,
        String phone,
        String email,
        String settlements,
        boolean isActive
) {}
