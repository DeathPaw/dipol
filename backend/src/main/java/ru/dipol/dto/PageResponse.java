package ru.dipol.dto;

import java.util.List;

/** Страница списка: данные + общее количество для пагинации. */
public record PageResponse<T>(List<T> items, long total, int page, int size) {

    public static <T> PageResponse<T> of(List<T> items, long total, int page, int size) {
        return new PageResponse<>(items, total, page, size);
    }
}
