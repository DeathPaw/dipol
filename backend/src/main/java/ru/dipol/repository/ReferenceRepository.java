package ru.dipol.repository;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.dipol.exception.EntityNotFoundException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Работа с простыми справочниками «код → название».
 *
 * <p>Их в ERP полтора десятка (источники заявок, финансовые классы, причины
 * ожидания и переноса, категории проблем, статьи кассы, типы спецтехники,
 * типы документов ТБ). Отдельный репозиторий и контроллер на каждый дали бы
 * 15 почти одинаковых пар классов, поэтому таблица выбирается по белому
 * списку — произвольное имя в SQL не подставляется.
 */
@Repository
public class ReferenceRepository {

    /** Белый список: ключ API → имя таблицы. Ничего вне него в SQL не попадает. */
    private static final Map<String, String> TABLES = Map.ofEntries(
            Map.entry("request-sources", "request_sources"),
            Map.entry("financial-classes", "financial_classes"),
            Map.entry("wait-reasons", "wait_reasons"),
            Map.entry("reschedule-reasons", "reschedule_reasons"),
            Map.entry("problem-categories", "problem_categories"),
            Map.entry("employee-roles", "employee_roles"),
            Map.entry("cash-categories", "cash_categories"),
            Map.entry("money-circuits", "money_circuits"),
            Map.entry("special-equipment-types", "special_equipment_types"),
            Map.entry("vehicle-expense-categories", "vehicle_expense_categories"),
            Map.entry("safety-doc-types", "safety_doc_types"),
            Map.entry("units", "units"),
            Map.entry("request-statuses", "request_statuses"),
            Map.entry("pay-scheme-types", "pay_scheme_types")
    );

    /** Справочники, редактировать которые нельзя: на их кодах держится логика. */
    private static final Set<String> READ_ONLY = Set.of("request-statuses", "units", "pay-scheme-types");

    private final NamedParameterJdbcTemplate jdbc;

    public ReferenceRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Set<String> keys() {
        return TABLES.keySet();
    }

    public boolean isReadOnly(String key) {
        return READ_ONLY.contains(key);
    }

    public List<Map<String, Object>> findAll(String key) {
        return jdbc.queryForList("SELECT * FROM " + table(key) + " ORDER BY sort_order, code", Map.of());
    }

    public void create(String key, String code, String name, Integer sortOrder) {
        Map<String, Object> p = new HashMap<>();
        p.put("c", code);
        p.put("n", name);
        p.put("s", sortOrder != null ? sortOrder : 100);
        jdbc.update("INSERT INTO " + table(key) + " (code, name, sort_order) VALUES (:c, :n, :s)", p);
    }

    public void update(String key, String code, String name, Integer sortOrder) {
        Map<String, Object> p = new HashMap<>();
        p.put("c", code);
        p.put("n", name);
        p.put("s", sortOrder != null ? sortOrder : 100);
        jdbc.update("UPDATE " + table(key) + " SET name = :n, sort_order = :s WHERE code = :c", p);
    }

    public void delete(String key, String code) {
        jdbc.update("DELETE FROM " + table(key) + " WHERE code = :c", Map.of("c", code));
    }

    private String table(String key) {
        String t = TABLES.get(key);
        if (t == null) throw new EntityNotFoundException("Справочник не найден: " + key);
        return t;
    }
}
