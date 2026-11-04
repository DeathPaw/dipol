package ru.dipol.repository;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Версионируемые нормативы вместо «магических чисел» в формулах (US-N6).
 * Значение всегда берётся на дату: смена норматива не переписывает прошлые
 * расчёты, она создаёт новую версию.
 */
@Repository
public class ParameterRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ParameterRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> findAll() {
        return jdbc.queryForList("""
                SELECT p.*,
                       v.value_num AS current_value_num,
                       v.value_text AS current_value_text,
                       v.valid_from AS current_valid_from,
                       v.comment AS current_comment
                FROM parameters p
                LEFT JOIN LATERAL (
                    SELECT * FROM parameter_versions pv
                    WHERE pv.parameter_id = p.id AND pv.valid_from <= CURRENT_DATE
                    ORDER BY pv.valid_from DESC LIMIT 1
                ) v ON TRUE
                ORDER BY p.code
                """, Map.of());
    }

    public List<Map<String, Object>> versions(Long parameterId) {
        return jdbc.queryForList(
                "SELECT * FROM parameter_versions WHERE parameter_id = :p ORDER BY valid_from DESC",
                Map.of("p", parameterId));
    }

    /** Значение норматива на дату. Null — значит норматив ещё не утверждён. */
    public BigDecimal numberOn(String code, LocalDate date) {
        List<BigDecimal> found = jdbc.queryForList("""
                SELECT v.value_num FROM parameters p
                JOIN parameter_versions v ON v.parameter_id = p.id
                WHERE p.code = :c AND v.valid_from <= :d AND (v.valid_to IS NULL OR v.valid_to > :d)
                ORDER BY v.valid_from DESC LIMIT 1
                """, Map.of("c", code, "d", date), BigDecimal.class);
        return found.isEmpty() ? null : found.get(0);
    }

    public BigDecimal currentNumber(String code) {
        return numberOn(code, LocalDate.now());
    }

    public Long createParameter(String code, String name, String unitCode,
                                String valueType, String description) {
        Map<String, Object> p = new HashMap<>();
        p.put("c", code);
        p.put("n", name);
        p.put("u", unitCode);
        p.put("t", valueType != null ? valueType : "NUMBER");
        p.put("d", description);
        return jdbc.queryForObject("""
                INSERT INTO parameters (code, name, unit_code, value_type, description)
                VALUES (:c, :n, :u, :t, :d) RETURNING id
                """, p, Long.class);
    }

    public void addVersion(Long parameterId, BigDecimal valueNum, String valueText,
                           LocalDate validFrom, String author, String comment) {
        Map<String, Object> p = new HashMap<>();
        p.put("p", parameterId);
        p.put("d", validFrom);
        // Предыдущая версия закрывается датой новой: периоды не пересекаются,
        // иначе «значение на дату» становится неоднозначным.
        jdbc.update("""
                UPDATE parameter_versions SET valid_to = :d
                WHERE parameter_id = :p AND valid_to IS NULL AND valid_from < :d
                """, p);
        Map<String, Object> ins = new HashMap<>();
        ins.put("p", parameterId);
        ins.put("num", valueNum);
        ins.put("txt", valueText);
        ins.put("d", validFrom);
        ins.put("a", author);
        ins.put("c", comment);
        jdbc.update("""
                INSERT INTO parameter_versions (parameter_id, value_num, value_text, valid_from, author, comment)
                VALUES (:p, :num, :txt, :d, :a, :c)
                """, ins);
    }

    // ---- диапазонные коэффициенты -------------------------------------------

    public List<Map<String, Object>> coefficientRanges(String code) {
        return jdbc.queryForList("""
                SELECT * FROM coefficient_ranges WHERE code = :c
                ORDER BY COALESCE(from_value, -1e9), valid_from
                """, Map.of("c", code));
    }

    /**
     * Коэффициент k по значению производительности p. Границы включения заданы
     * явно (from_inclusive/to_inclusive) — «p=1,10» не должно попадать в два
     * диапазона сразу (US-J3).
     */
    public BigDecimal coefficient(String code, BigDecimal value, LocalDate date) {
        if (value == null) return null;
        List<BigDecimal> found = jdbc.queryForList("""
                SELECT k FROM coefficient_ranges
                WHERE code = :c
                  AND valid_from <= :d AND (valid_to IS NULL OR valid_to > :d)
                  AND (from_value IS NULL
                       OR (from_inclusive AND :v >= from_value)
                       OR (NOT from_inclusive AND :v > from_value))
                  AND (to_value IS NULL
                       OR (to_inclusive AND :v <= to_value)
                       OR (NOT to_inclusive AND :v < to_value))
                ORDER BY COALESCE(from_value, -1e9) DESC LIMIT 1
                """, Map.of("c", code, "v", value, "d", date), BigDecimal.class);
        return found.isEmpty() ? null : found.get(0);
    }

    public void saveCoefficientRange(Map<String, Object> r) {
        Map<String, Object> p = new HashMap<>(r);
        jdbc.update("""
                INSERT INTO coefficient_ranges (code, from_value, from_inclusive, to_value, to_inclusive,
                                                k, valid_from, valid_to, author, comment)
                VALUES (:code, :from_value, :from_inclusive, :to_value, :to_inclusive,
                        :k, :valid_from, :valid_to, :author, :comment)
                """, p);
    }
}
