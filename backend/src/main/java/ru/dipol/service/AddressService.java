package ru.dipol.service;

import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Нормализация адреса и построение ключа сопоставления.
 *
 * <p>Один и тот же дом приходит из разных источников в разных написаниях:
 * «гп Сиверский, ул Военный городок, д. 12», «Сиверский, Военный городок, 12»,
 * «п. Сиверский ул.Военный Городок д.12». Точный MATCH по строке, на котором
 * держатся действующие таблицы, здесь ненадёжен — поэтому ключ собирается из
 * значимых частей: населённый пункт, улица, номер дома.
 *
 * <p>Ключ не является первичным идентификатором дома: он нужен только для
 * поиска кандидата. Главный ключ — внутренний id (US-A6).
 */
@Service
public class AddressService {

    /** Типы населённых пунктов и улиц: на сам адрес не влияют, из ключа убираются. */
    private static final List<String> TYPE_WORDS = List.of(
            "гп", "рп", "пгт", "тер", "снт", "днп",
            "ул", "улица", "пр", "пркт", "проспект", "пер", "переулок",
            "ш", "шоссе", "наб", "набережная", "б", "бул", "бульвар",
            "пл", "площадь", "проезд", "линия", "туп", "тупик",
            "д", "дом", "к", "корп", "корпус", "стр", "строение", "лит", "литера"
    );

    private static final Pattern NON_SIGNIFICANT = Pattern.compile("[^a-zа-я0-9/\\s]");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    /**
     * Ключ сопоставления: строчные буквы, без типов объектов и пунктуации,
     * слова отсортированы. Сортировка нужна, потому что источники переставляют
     * части адреса местами («Шпаньково, ул Песочная, 28» и «ул Песочная, 28, Шпаньково»).
     */
    public String key(String rawAddress) {
        if (rawAddress == null || rawAddress.isBlank()) return "";
        String s = rawAddress.toLowerCase(Locale.ROOT).replace('ё', 'е');
        s = Normalizer.normalize(s, Normalizer.Form.NFKC);
        // «д.12» и «д 12» — одно и то же; точка склеивает тип с номером.
        s = s.replace('.', ' ').replace(',', ' ').replace('-', ' ');
        s = NON_SIGNIFICANT.matcher(s).replaceAll(" ");
        s = SPACES.matcher(s).replaceAll(" ").trim();

        return java.util.Arrays.stream(s.split(" "))
                .filter(w -> !w.isBlank())
                .filter(w -> !TYPE_WORDS.contains(w))
                .sorted()
                .reduce((a, b) -> a + " " + b)
                .orElse("");
    }

    /**
     * Отображаемое представление: схлопывает лишние пробелы и убирает
     * повторяющиеся запятые, но порядок и состав частей сохраняет.
     */
    public String normalizeDisplay(String rawAddress) {
        if (rawAddress == null) return null;
        String s = rawAddress.trim().replaceAll("\\s*,\\s*", ", ");
        s = SPACES.matcher(s).replaceAll(" ");
        return s.replaceAll("(,\\s*)+$", "");
    }

    /**
     * Подъезд по номеру квартиры. Точный расчёт — по матрице подъездов дома;
     * равномерное деление используется только как подсказка и помечается
     * как неточное: на нестандартных домах оно ошибается (US-F2).
     */
    public Integer guessEntrance(Integer apartment, Integer apartmentsPerEntrance, Integer entrancesCount) {
        if (apartment == null || apartment <= 0) return null;
        if (apartmentsPerEntrance == null || apartmentsPerEntrance <= 0) return null;
        int e = (int) Math.ceil(apartment / (double) apartmentsPerEntrance);
        if (entrancesCount != null && entrancesCount > 0 && e > entrancesCount) return entrancesCount;
        return Math.max(e, 1);
    }
}
