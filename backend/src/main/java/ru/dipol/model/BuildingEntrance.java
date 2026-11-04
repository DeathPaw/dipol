package ru.dipol.model;

/** Подъезд с диапазоном квартир: из него вычисляется подъезд заявки (US-F2). */
public record BuildingEntrance(
        Long id,
        Long buildingId,
        Integer number,
        Integer apartmentFrom,
        Integer apartmentTo,
        Integer floors,
        String note
) {}
