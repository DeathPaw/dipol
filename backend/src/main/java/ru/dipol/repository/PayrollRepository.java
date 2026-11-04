package ru.dipol.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class PayrollRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public PayrollRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- индивидуальные условия оплаты --------------------------------------

    /**
     * Действующее условие оплаты сотрудника в проекте на дату. Именно связка
     * «сотрудник + проект + период» определяет схему: в одном проекте люди
     * могут одновременно работать за смену и сдельно (US-E4, US-J1).
     */
    public Optional<Map<String, Object>> payTerms(Long employeeId, Long projectId, LocalDate date) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT * FROM employee_pay_terms
                WHERE employee_id = :e AND project_id = :p AND is_active
                  AND valid_from <= :d AND (valid_to IS NULL OR valid_to >= :d)
                ORDER BY valid_from DESC, version DESC LIMIT 1
                """, Map.of("e", employeeId, "p", projectId, "d", date));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<Map<String, Object>> payTermsList(Long employeeId, Long projectId) {
        StringBuilder sql = new StringBuilder("""
                SELECT t.*, e.full_name AS employee_name, p.code AS project_code, p.name AS project_name,
                       s.name AS scheme_name
                FROM employee_pay_terms t
                JOIN employees e ON e.id = t.employee_id
                JOIN projects p ON p.id = t.project_id
                JOIN pay_scheme_types s ON s.code = t.scheme_code
                WHERE 1=1
                """);
        Map<String, Object> params = new HashMap<>();
        if (employeeId != null) {
            sql.append(" AND t.employee_id = :e");
            params.put("e", employeeId);
        }
        if (projectId != null) {
            sql.append(" AND t.project_id = :p");
            params.put("p", projectId);
        }
        sql.append(" ORDER BY e.full_name, p.code, t.valid_from DESC");
        return jdbc.queryForList(sql.toString(), params);
    }

    /**
     * Новое условие оплаты = новая версия. Предыдущая закрывается датой начала
     * новой, закрытые начисления остаются на своей версии (US-J1).
     */
    public Long createPayTerms(Long employeeId, Long projectId, String schemeCode, LocalDate validFrom,
                               BigDecimal rate, String formulaCode, String paramsJson,
                               String author, String comment) {
        Integer prevVersion = jdbc.queryForObject("""
                SELECT COALESCE(MAX(version),0) FROM employee_pay_terms
                WHERE employee_id = :e AND project_id = :p
                """, Map.of("e", employeeId, "p", projectId), Integer.class);
        jdbc.update("""
                UPDATE employee_pay_terms SET valid_to = :d - INTERVAL '1 day', is_active = FALSE
                WHERE employee_id = :e AND project_id = :p AND valid_to IS NULL AND valid_from < :d
                """, Map.of("e", employeeId, "p", projectId, "d", validFrom));
        var kh = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO employee_pay_terms (employee_id, project_id, scheme_code, version, valid_from,
                    rate, formula_code, params_json, author, comment)
                VALUES (:e, :p, :s, :v, :d, :r, :f, :j, :a, :c)
                """, new MapSqlParameterSource()
                        .addValue("e", employeeId).addValue("p", projectId).addValue("s", schemeCode)
                        .addValue("v", (prevVersion != null ? prevVersion : 0) + 1)
                        .addValue("d", validFrom).addValue("r", rate).addValue("f", formulaCode)
                        .addValue("j", paramsJson).addValue("a", author).addValue("c", comment),
                kh, new String[]{"id"});
        return ((Number) kh.getKeys().get("id")).longValue();
    }

    // ---- тарифы -------------------------------------------------------------

    /**
     * Подбор тарифа. Чем уже правило, тем выше приоритет: конкретный сотрудник
     * важнее роли, конкретный проект важнее конфигурации. Система возвращает
     * сработавшее правило целиком, чтобы его было видно в карточке начисления.
     */
    public Optional<Map<String, Object>> findRate(Long skuId, Long projectId, Long employeeId,
                                                  String roleCode, LocalDate date) {
        Map<String, Object> p = new HashMap<>();
        p.put("sku", skuId);
        p.put("project", projectId);
        p.put("employee", employeeId);
        p.put("role", roleCode);
        p.put("d", date);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT r.*,
                       (CASE WHEN r.employee_id IS NOT NULL THEN 8 ELSE 0 END
                      + CASE WHEN r.sku_id IS NOT NULL THEN 4 ELSE 0 END
                      + CASE WHEN r.project_id IS NOT NULL THEN 2 ELSE 0 END
                      + CASE WHEN r.employee_role_code IS NOT NULL THEN 1 ELSE 0 END) AS specificity
                FROM payroll_rates r
                WHERE r.valid_from <= :d AND (r.valid_to IS NULL OR r.valid_to >= :d)
                  AND (r.sku_id IS NULL OR r.sku_id = :sku)
                  AND (r.project_id IS NULL OR r.project_id = :project)
                  AND (r.employee_id IS NULL OR r.employee_id = :employee)
                  AND (r.employee_role_code IS NULL OR r.employee_role_code = :role)
                ORDER BY specificity DESC, r.priority ASC, r.valid_from DESC
                LIMIT 1
                """, p);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** Пересечения правил без явного приоритета — повод предупредить (US-J3). */
    public List<Map<String, Object>> ambiguousRates() {
        return jdbc.queryForList("""
                SELECT a.id AS id_a, b.id AS id_b, a.sku_id, a.project_id, a.employee_id,
                       a.employee_role_code, a.valid_from AS from_a, b.valid_from AS from_b
                FROM payroll_rates a JOIN payroll_rates b ON b.id > a.id
                WHERE a.priority = b.priority
                  AND a.sku_id IS NOT DISTINCT FROM b.sku_id
                  AND a.project_id IS NOT DISTINCT FROM b.project_id
                  AND a.employee_id IS NOT DISTINCT FROM b.employee_id
                  AND a.employee_role_code IS NOT DISTINCT FROM b.employee_role_code
                  AND a.valid_from <= COALESCE(b.valid_to, DATE '9999-12-31')
                  AND b.valid_from <= COALESCE(a.valid_to, DATE '9999-12-31')
                """, Map.of());
    }

    public List<Map<String, Object>> rates(Long projectId, Long skuId) {
        StringBuilder sql = new StringBuilder("""
                SELECT r.*, s.name AS sku_name, p.code AS project_code, e.full_name AS employee_name
                FROM payroll_rates r
                LEFT JOIN sku s ON s.id = r.sku_id
                LEFT JOIN projects p ON p.id = r.project_id
                LEFT JOIN employees e ON e.id = r.employee_id
                WHERE 1=1
                """);
        Map<String, Object> params = new HashMap<>();
        if (projectId != null) {
            sql.append(" AND r.project_id = :p");
            params.put("p", projectId);
        }
        if (skuId != null) {
            sql.append(" AND r.sku_id = :s");
            params.put("s", skuId);
        }
        sql.append(" ORDER BY r.valid_from DESC, r.id DESC");
        return jdbc.queryForList(sql.toString(), params);
    }

    public Long createRate(Map<String, Object> r) {
        var kh = new GeneratedKeyHolder();
        var p = new MapSqlParameterSource()
                .addValue("sku", numOrNull(r.get("sku_id")))
                .addValue("project", numOrNull(r.get("project_id")))
                .addValue("config", numOrNull(r.get("project_config_id")))
                .addValue("role", r.get("employee_role_code"))
                .addValue("employee", numOrNull(r.get("employee_id")))
                .addValue("unit", r.get("unit_code"))
                .addValue("value", new BigDecimal(String.valueOf(r.get("value"))))
                .addValue("from", LocalDate.parse(String.valueOf(r.get("valid_from"))))
                .addValue("to", r.get("valid_to") != null ? LocalDate.parse(String.valueOf(r.get("valid_to"))) : null)
                .addValue("prio", r.get("priority") != null ? ((Number) r.get("priority")).intValue() : 100)
                .addValue("author", r.get("author"))
                .addValue("comment", r.get("comment"));
        jdbc.update("""
                INSERT INTO payroll_rates (sku_id, project_id, project_config_id, employee_role_code,
                    employee_id, unit_code, value, valid_from, valid_to, priority, author, comment)
                VALUES (:sku, :project, :config, :role, :employee, :unit, :value, :from, :to, :prio, :author, :comment)
                """, p, kh, new String[]{"id"});
        return ((Number) kh.getKeys().get("id")).longValue();
    }

    // ---- начисления ---------------------------------------------------------

    public Long insertEntry(Map<String, Object> e) {
        var kh = new GeneratedKeyHolder();
        var p = new MapSqlParameterSource()
                .addValue("emp", e.get("employee_id"))
                .addValue("proj", e.get("project_id"))
                .addValue("req", e.get("request_id"))
                .addValue("item", e.get("request_item_id"))
                .addValue("trip", e.get("trip_id"))
                .addValue("rk3", e.get("rk3_object_id"))
                .addValue("basis", e.get("basis_type"))
                .addValue("month", e.get("period_month"))
                .addValue("date", e.get("entry_date"))
                .addValue("sku", e.get("sku_id"))
                .addValue("qty", e.get("qty"))
                .addValue("unit", e.get("unit_code"))
                .addValue("rate", e.get("rate"))
                .addValue("k", e.getOrDefault("coefficient", BigDecimal.ONE))
                .addValue("formula", e.get("formula"))
                .addValue("amount", e.get("amount"))
                .addValue("terms", e.get("pay_terms_id"))
                .addValue("termsV", e.get("pay_terms_version"))
                .addValue("rateId", e.get("rate_id"))
                .addValue("manual", Boolean.TRUE.equals(e.get("is_manual")))
                .addValue("reason", e.get("reason"))
                .addValue("author", e.get("created_by"));
        jdbc.update("""
                INSERT INTO payroll_entries (employee_id, project_id, request_id, request_item_id, trip_id,
                    rk3_object_id, basis_type, period_month, entry_date, sku_id, qty, unit_code, rate,
                    coefficient, formula, amount, pay_terms_id, pay_terms_version, rate_id, is_manual,
                    reason, created_by)
                VALUES (:emp, :proj, :req, :item, :trip, :rk3, :basis, :month, :date, :sku, :qty, :unit,
                        :rate, :k, :formula, :amount, :terms, :termsV, :rateId, :manual, :reason, :author)
                """, p, kh, new String[]{"id"});
        return ((Number) kh.getKeys().get("id")).longValue();
    }

    /** Ранее рассчитанные черновые начисления по позиции — чтобы не задваивать. */
    public void removeDraftEntriesForItem(Long itemId) {
        jdbc.update("DELETE FROM payroll_entries WHERE request_item_id = :i AND status = 'DRAFT'",
                Map.of("i", itemId));
    }

    public List<Map<String, Object>> entries(Long employeeId, Long projectId, LocalDate from,
                                             LocalDate to, String status) {
        StringBuilder sql = new StringBuilder("""
                SELECT pe.*, e.full_name AS employee_name, p.code AS project_code, p.name AS project_name,
                       s.name AS sku_name, r.number AS request_number, t.name AS scheme_name
                FROM payroll_entries pe
                JOIN employees e ON e.id = pe.employee_id
                JOIN projects p ON p.id = pe.project_id
                LEFT JOIN sku s ON s.id = pe.sku_id
                LEFT JOIN requests r ON r.id = pe.request_id
                LEFT JOIN employee_pay_terms ept ON ept.id = pe.pay_terms_id
                LEFT JOIN pay_scheme_types t ON t.code = ept.scheme_code
                WHERE 1=1
                """);
        Map<String, Object> params = new HashMap<>();
        if (employeeId != null) {
            sql.append(" AND pe.employee_id = :e");
            params.put("e", employeeId);
        }
        if (projectId != null) {
            sql.append(" AND pe.project_id = :p");
            params.put("p", projectId);
        }
        if (from != null) {
            sql.append(" AND pe.entry_date >= :from");
            params.put("from", from);
        }
        if (to != null) {
            sql.append(" AND pe.entry_date <= :to");
            params.put("to", to);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND pe.status = :s");
            params.put("s", status);
        }
        sql.append(" ORDER BY pe.entry_date DESC, pe.id DESC");
        return jdbc.queryForList(sql.toString(), params);
    }

    public void setEntryStatus(List<Long> ids, String status) {
        if (ids.isEmpty()) return;
        jdbc.update("UPDATE payroll_entries SET status = :s, updated_at = NOW() WHERE id IN (:ids)",
                Map.of("s", status, "ids", ids));
    }

    /** Сторно начисления: исходная запись остаётся, добавляется обратная. */
    public Long reverseEntry(Long id, String reason, String author) {
        var kh = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO payroll_entries (employee_id, project_id, request_id, request_item_id, trip_id,
                    rk3_object_id, basis_type, period_month, entry_date, sku_id, qty, unit_code, rate,
                    coefficient, formula, amount, pay_terms_id, pay_terms_version, rate_id, status,
                    is_manual, reason, reverses_id, created_by)
                SELECT employee_id, project_id, request_id, request_item_id, trip_id, rk3_object_id,
                       basis_type, period_month, CURRENT_DATE, sku_id, qty, unit_code, rate, coefficient,
                       'Сторно начисления #' || id, -amount, pay_terms_id, pay_terms_version, rate_id,
                       'APPROVED', TRUE, :reason, id, :author
                FROM payroll_entries WHERE id = :id
                """, new MapSqlParameterSource().addValue("id", id).addValue("reason", reason)
                     .addValue("author", author), kh, new String[]{"id"});
        jdbc.update("UPDATE payroll_entries SET status = 'REVERSED' WHERE id = :id", Map.of("id", id));
        return ((Number) kh.getKeys().get("id")).longValue();
    }

    // ---- взаиморасчёты ------------------------------------------------------

    /** Начислено / компенсации / авансы / выплачено / остаток по сотруднику (US-J5). */
    public List<Map<String, Object>> settlements(LocalDate from, LocalDate to) {
        return jdbc.queryForList("""
                SELECT e.id AS employee_id, e.full_name,
                  COALESCE((SELECT SUM(pe.amount) FROM payroll_entries pe
                            WHERE pe.employee_id = e.id AND pe.status <> 'REVERSED'
                              AND pe.entry_date BETWEEN :from AND :to), 0) AS accrued,
                  COALESCE((SELECT SUM(a.amount) FROM payroll_adjustments a
                            WHERE a.employee_id = e.id AND a.kind IN ('BONUS','CORRECTION','FOREMAN')
                              AND a.adj_date BETWEEN :from AND :to), 0) AS bonuses,
                  COALESCE((SELECT SUM(a.amount) FROM payroll_adjustments a
                            WHERE a.employee_id = e.id AND a.kind = 'PENALTY'
                              AND a.adj_date BETWEEN :from AND :to), 0) AS penalties,
                  COALESCE((SELECT SUM(a.amount) FROM payroll_adjustments a
                            WHERE a.employee_id = e.id AND a.kind = 'ADVANCE'
                              AND a.adj_date BETWEEN :from AND :to), 0) AS advances,
                  COALESCE((SELECT SUM(c.amount) FROM compensations c
                            WHERE c.employee_id = e.id AND c.spent_on BETWEEN :from AND :to), 0) AS compensations,
                  COALESCE((SELECT SUM(p.amount) FROM payouts p
                            WHERE p.employee_id = e.id AND p.paid_on BETWEEN :from AND :to), 0) AS paid
                FROM employees e
                WHERE e.is_active
                ORDER BY e.full_name
                """, Map.of("from", from, "to", to));
    }

    private static Long numOrNull(Object v) {
        return v == null ? null : ((Number) v).longValue();
    }
}
