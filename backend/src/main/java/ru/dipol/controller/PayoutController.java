package ru.dipol.controller;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.web.bind.annotation.*;
import ru.dipol.audit.CurrentUser;
import ru.dipol.exception.BusinessRuleException;
import ru.dipol.service.AuditService;
import ru.dipol.service.PermissionService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Выплаты сотрудникам. Дата выдачи денег и расчётный период — разные вещи
 * (US-L5): выплата 2 июля может относиться к июню. Переплата не исчезает,
 * она видна как отрицательный остаток (US-J5).
 */
@RestController
@RequestMapping("/api/payouts")
public class PayoutController {

    private final NamedParameterJdbcTemplate jdbc;
    private final PermissionService permissions;
    private final AuditService audit;

    public PayoutController(NamedParameterJdbcTemplate jdbc, PermissionService permissions,
                            AuditService audit) {
        this.jdbc = jdbc;
        this.permissions = permissions;
        this.audit = audit;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) Long employeeId,
                                          @RequestParam(required = false) LocalDate from,
                                          @RequestParam(required = false) LocalDate to) {
        permissions.require("PAYROLL_VIEW", null);
        StringBuilder sql = new StringBuilder("""
                SELECT p.*, e.full_name AS employee_name,
                       COALESCE((SELECT SUM(a.amount) FROM payout_allocations a WHERE a.payout_id = p.id), 0)
                           AS allocated
                FROM payouts p JOIN employees e ON e.id = p.employee_id WHERE 1=1
                """);
        Map<String, Object> params = new HashMap<>();
        if (employeeId != null) {
            sql.append(" AND p.employee_id = :e");
            params.put("e", employeeId);
        }
        if (from != null) {
            sql.append(" AND p.paid_on >= :f");
            params.put("f", from);
        }
        if (to != null) {
            sql.append(" AND p.paid_on <= :t");
            params.put("t", to);
        }
        sql.append(" ORDER BY p.paid_on DESC, p.id DESC");
        return jdbc.queryForList(sql.toString(), params);
    }

    @PostMapping
    @SuppressWarnings("unchecked")
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        permissions.require("PAYROLL_EDIT", null);
        BigDecimal amount = dec(body.get("amount"));
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessRuleException("Сумма выплаты должна быть положительной");
        }
        var kh = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO payouts (employee_id, paid_on, amount, channel, period_from, period_to,
                                     cash_transaction_id, comment, created_by)
                VALUES (:e, :d, :a, :ch, :pf, :pt, :tx, :c, :author)
                """, new MapSqlParameterSource()
                        .addValue("e", num(body.get("employee_id")))
                        .addValue("d", LocalDate.parse(String.valueOf(body.get("paid_on"))))
                        .addValue("a", amount)
                        .addValue("ch", body.getOrDefault("channel", "CASH"))
                        .addValue("pf", date(body.get("period_from")))
                        .addValue("pt", date(body.get("period_to")))
                        .addValue("tx", num(body.get("cash_transaction_id")))
                        .addValue("c", body.get("comment"))
                        .addValue("author", CurrentUser.name()),
                kh, new String[]{"id"});
        Long id = ((Number) kh.getKeys().get("id")).longValue();

        List<Map<String, Object>> allocations =
                (List<Map<String, Object>>) body.getOrDefault("allocations", List.of());
        allocate(id, allocations);
        audit.created("PAYOUT", id);
        return Map.of("id", id);
    }

    /** Разнесение выплаты на конкретные начисления, компенсации или период. */
    @PutMapping("/{id}/allocations")
    public Map<String, Object> setAllocations(@PathVariable Long id,
                                              @RequestBody List<Map<String, Object>> allocations) {
        permissions.require("PAYROLL_EDIT", null);
        jdbc.update("DELETE FROM payout_allocations WHERE payout_id = :p", Map.of("p", id));
        allocate(id, allocations);
        return Map.of("status", "ok", "allocations", allocations(id));
    }

    @GetMapping("/{id}/allocations")
    public List<Map<String, Object>> allocations(@PathVariable Long id) {
        return jdbc.queryForList("""
                SELECT a.*, pe.formula AS entry_formula, pe.entry_date, c.title AS compensation_title
                FROM payout_allocations a
                LEFT JOIN payroll_entries pe ON pe.id = a.payroll_entry_id
                LEFT JOIN compensations c ON c.id = a.compensation_id
                WHERE a.payout_id = :p ORDER BY a.id
                """, Map.of("p", id));
    }

    private void allocate(Long payoutId, List<Map<String, Object>> allocations) {
        for (var a : allocations) {
            Map<String, Object> p = new HashMap<>();
            p.put("p", payoutId);
            p.put("pe", num(a.get("payroll_entry_id")));
            p.put("c", num(a.get("compensation_id")));
            p.put("adj", num(a.get("adjustment_id")));
            p.put("m", date(a.get("period_month")));
            p.put("amt", dec(a.get("amount")));
            jdbc.update("""
                    INSERT INTO payout_allocations (payout_id, payroll_entry_id, compensation_id,
                                                    adjustment_id, period_month, amount)
                    VALUES (:p, :pe, :c, :adj, :m, :amt)
                    """, p);
        }
    }

    /**
     * Остаток по сотруднику: начислено + премии − штрафы + компенсации
     * − авансы − выплачено. Отрицательный остаток = переплата, она переносится
     * на следующий период, а не обнуляется.
     */
    @GetMapping("/balance")
    public List<Map<String, Object>> balance(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        permissions.require("PAYROLL_VIEW", null);
        return jdbc.queryForList("""
                SELECT e.id AS employee_id, e.full_name,
                  COALESCE(acc.total, 0) AS accrued,
                  COALESCE(bon.total, 0) AS bonuses,
                  COALESCE(pen.total, 0) AS penalties,
                  COALESCE(adv.total, 0) AS advances,
                  COALESCE(comp.total, 0) AS compensations,
                  COALESCE(pay.total, 0) AS paid,
                  COALESCE(acc.total,0) + COALESCE(bon.total,0) - COALESCE(pen.total,0)
                    + COALESCE(comp.total,0) - COALESCE(adv.total,0) - COALESCE(pay.total,0) AS balance
                FROM employees e
                LEFT JOIN (SELECT employee_id, SUM(amount) total FROM payroll_entries
                           WHERE status <> 'REVERSED' AND entry_date BETWEEN :f AND :t
                           GROUP BY employee_id) acc ON acc.employee_id = e.id
                LEFT JOIN (SELECT employee_id, SUM(amount) total FROM payroll_adjustments
                           WHERE kind IN ('BONUS','CORRECTION','FOREMAN') AND adj_date BETWEEN :f AND :t
                           GROUP BY employee_id) bon ON bon.employee_id = e.id
                LEFT JOIN (SELECT employee_id, SUM(amount) total FROM payroll_adjustments
                           WHERE kind = 'PENALTY' AND adj_date BETWEEN :f AND :t
                           GROUP BY employee_id) pen ON pen.employee_id = e.id
                LEFT JOIN (SELECT employee_id, SUM(amount) total FROM payroll_adjustments
                           WHERE kind = 'ADVANCE' AND adj_date BETWEEN :f AND :t
                           GROUP BY employee_id) adv ON adv.employee_id = e.id
                LEFT JOIN (SELECT employee_id, SUM(amount) total FROM compensations
                           WHERE spent_on BETWEEN :f AND :t GROUP BY employee_id) comp ON comp.employee_id = e.id
                LEFT JOIN (SELECT employee_id, SUM(amount) total FROM payouts
                           WHERE paid_on BETWEEN :f AND :t GROUP BY employee_id) pay ON pay.employee_id = e.id
                WHERE e.is_active
                ORDER BY e.full_name
                """, Map.of("f", from, "t", to));
    }

    private static Long num(Object v) {
        return v == null ? null : ((Number) v).longValue();
    }

    private static BigDecimal dec(Object v) {
        if (v == null || String.valueOf(v).isBlank()) return null;
        return new BigDecimal(String.valueOf(v));
    }

    private static LocalDate date(Object v) {
        if (v == null || String.valueOf(v).isBlank()) return null;
        return LocalDate.parse(String.valueOf(v));
    }
}
