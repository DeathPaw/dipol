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
import java.util.UUID;

/**
 * Управленческие кассы.
 *
 * <p>Модель «откуда → куда» повторяет действующую книгу, но ID операции —
 * UUID, а не строка, зависящая от номера строки листа (миграция P1).
 * Проведённая операция не удаляется: исправление выполняется сторно (US-L6).
 * Перемещение между собственными кассами расходом предприятия не считается.
 */
@RestController
@RequestMapping("/api/finance")
public class FinanceController {

    private final NamedParameterJdbcTemplate jdbc;
    private final PermissionService permissions;
    private final AuditService audit;

    public FinanceController(NamedParameterJdbcTemplate jdbc, PermissionService permissions,
                             AuditService audit) {
        this.jdbc = jdbc;
        this.permissions = permissions;
        this.audit = audit;
    }

    // ---- счета и контрагенты ------------------------------------------------

    @GetMapping("/accounts")
    public List<Map<String, Object>> accounts(@RequestParam(defaultValue = "false") boolean includeInactive) {
        permissions.require("FINANCE_VIEW", "Доступ к кассам требует права «Просмотр касс»");
        return jdbc.queryForList("""
                SELECT a.*, c.name AS circuit_name FROM cash_accounts a
                JOIN money_circuits c ON c.code = a.circuit_code
                """ + (includeInactive ? "" : " WHERE a.is_active")
                + " ORDER BY a.sort_order, a.name", Map.of());
    }

    @PostMapping("/accounts")
    public Map<String, Object> createAccount(@RequestBody Map<String, Object> body) {
        permissions.require("FINANCE_EDIT", null);
        var kh = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO cash_accounts (name, account_type, circuit_code, owner, legal_entity,
                                           currency, is_real, sort_order)
                VALUES (:n, :t, :c, :o, :le, :cur, :real, :ord)
                """, new MapSqlParameterSource()
                        .addValue("n", body.get("name"))
                        .addValue("t", body.getOrDefault("account_type", "CASH"))
                        .addValue("c", body.getOrDefault("circuit_code", "DIPOL"))
                        .addValue("o", body.get("owner"))
                        .addValue("le", body.get("legal_entity"))
                        .addValue("cur", body.getOrDefault("currency", "RUB"))
                        .addValue("real", !Boolean.FALSE.equals(body.get("is_real")))
                        .addValue("ord", body.getOrDefault("sort_order", 100)),
                kh, new String[]{"id"});
        return Map.of("id", ((Number) kh.getKeys().get("id")).longValue());
    }

    @GetMapping("/counterparties")
    public List<Map<String, Object>> counterparties() {
        permissions.require("FINANCE_VIEW", null);
        return jdbc.queryForList("SELECT * FROM counterparties WHERE is_active ORDER BY name", Map.of());
    }

    @PostMapping("/counterparties")
    public Map<String, Object> createCounterparty(@RequestBody Map<String, Object> body) {
        permissions.require("FINANCE_EDIT", null);
        Long id = jdbc.queryForObject("""
                INSERT INTO counterparties (name, kind, client_id, employee_id, supplier_id)
                VALUES (:n, :k, :c, :e, :s) RETURNING id
                """, new MapSqlParameterSource()
                        .addValue("n", body.get("name"))
                        .addValue("k", body.getOrDefault("kind", "OTHER"))
                        .addValue("c", num(body.get("client_id")))
                        .addValue("e", num(body.get("employee_id")))
                        .addValue("s", num(body.get("supplier_id"))), Long.class);
        return Map.of("id", id);
    }

    // ---- операции -----------------------------------------------------------

    @GetMapping("/transactions")
    public List<Map<String, Object>> transactions(@RequestParam(required = false) LocalDate from,
                                                  @RequestParam(required = false) LocalDate to,
                                                  @RequestParam(required = false) String circuit,
                                                  @RequestParam(required = false) Long projectId,
                                                  @RequestParam(required = false) Long accountId,
                                                  @RequestParam(required = false) String direction,
                                                  @RequestParam(defaultValue = "500") int limit) {
        permissions.require("FINANCE_VIEW", null);
        StringBuilder sql = new StringBuilder("""
                SELECT t.*, af.name AS from_account_name, at.name AS to_account_name,
                       cp.name AS counterparty_name, cc.name AS category_name, p.code AS project_code
                FROM cash_transactions t
                LEFT JOIN cash_accounts af ON af.id = t.from_account_id
                LEFT JOIN cash_accounts at ON at.id = t.to_account_id
                LEFT JOIN counterparties cp ON cp.id = t.counterparty_id
                LEFT JOIN cash_categories cc ON cc.code = t.category_code
                LEFT JOIN projects p ON p.id = t.project_id
                WHERE 1=1
                """);
        Map<String, Object> pr = new HashMap<>();
        if (from != null) {
            sql.append(" AND t.occurred_on >= :f");
            pr.put("f", from);
        }
        if (to != null) {
            sql.append(" AND t.occurred_on <= :t");
            pr.put("t", to);
        }
        if (circuit != null && !circuit.isBlank()) {
            sql.append(" AND t.circuit_code = :c");
            pr.put("c", circuit);
        }
        if (projectId != null) {
            sql.append(" AND t.project_id = :p");
            pr.put("p", projectId);
        }
        if (accountId != null) {
            sql.append(" AND (t.from_account_id = :a OR t.to_account_id = :a)");
            pr.put("a", accountId);
        }
        if (direction != null && !direction.isBlank()) {
            sql.append(" AND t.direction = :d");
            pr.put("d", direction);
        }
        sql.append(" ORDER BY t.occurred_on DESC, t.id DESC LIMIT :lim");
        pr.put("lim", limit);
        return jdbc.queryForList(sql.toString(), pr);
    }

    @PostMapping("/transactions")
    @SuppressWarnings("unchecked")
    public Map<String, Object> createTransaction(@RequestBody Map<String, Object> body) {
        permissions.require("FINANCE_EDIT", "Кассовые операции требуют права «Кассовые операции»");
        BigDecimal amount = dec(body.get("amount"));
        if (amount == null || amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessRuleException("Сумма операции не может быть отрицательной: "
                    + "для исправления используйте сторно");
        }
        String direction = String.valueOf(body.getOrDefault("direction", "EXPENSE"));
        Long fromId = num(body.get("from_account_id"));
        Long toId = num(body.get("to_account_id"));
        if ("TRANSFER".equals(direction) && (fromId == null || toId == null)) {
            throw new BusinessRuleException("Для перемещения нужны и счёт-источник, и счёт-получатель");
        }

        var kh = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO cash_transactions (uuid, legacy_id, occurred_on, direction, from_account_id,
                    to_account_id, amount, category_code, counterparty_id, project_id, circuit_code,
                    period_from, period_to, comment, created_by)
                VALUES (:uuid, :legacy, :d, :dir, :from, :to, :amt, :cat, :cp, :proj, :circ,
                        :pf, :pt, :c, :author)
                """, new MapSqlParameterSource()
                        .addValue("uuid", UUID.randomUUID())
                        .addValue("legacy", body.get("legacy_id"))
                        .addValue("d", LocalDate.parse(String.valueOf(body.get("occurred_on"))))
                        .addValue("dir", direction)
                        .addValue("from", fromId)
                        .addValue("to", toId)
                        .addValue("amt", amount)
                        .addValue("cat", body.get("category_code"))
                        .addValue("cp", num(body.get("counterparty_id")))
                        .addValue("proj", num(body.get("project_id")))
                        .addValue("circ", body.get("circuit_code"))
                        .addValue("pf", date(body.get("period_from")))
                        .addValue("pt", date(body.get("period_to")))
                        .addValue("c", body.get("comment"))
                        .addValue("author", CurrentUser.name()),
                kh, new String[]{"id"});
        Long id = ((Number) kh.getKeys().get("id")).longValue();

        List<Map<String, Object>> allocations =
                (List<Map<String, Object>>) body.getOrDefault("allocations", List.of());
        for (var a : allocations) {
            Map<String, Object> p = new HashMap<>();
            p.put("t", id);
            p.put("bt", a.getOrDefault("basis_type", "OTHER"));
            p.put("pe", num(a.get("payroll_entry_id")));
            p.put("c", num(a.get("compensation_id")));
            p.put("po", num(a.get("purchase_order_id")));
            p.put("es", num(a.get("equipment_shift_id")));
            p.put("amt", dec(a.get("amount")));
            p.put("cm", a.get("comment"));
            jdbc.update("""
                    INSERT INTO transaction_allocations (transaction_id, basis_type, payroll_entry_id,
                        compensation_id, purchase_order_id, equipment_shift_id, amount, comment)
                    VALUES (:t, :bt, :pe, :c, :po, :es, :amt, :cm)
                    """, p);
        }
        audit.created("CASH_TRANSACTION", id);
        return Map.of("id", id);
    }

    /** Сторно. Удалять проведённую операцию нельзя, только создать обратную. */
    @PostMapping("/transactions/{id}/reverse")
    public Map<String, Object> reverse(@PathVariable Long id, @RequestBody Map<String, String> body) {
        permissions.require("FINANCE_REVERSE", "Сторно требует отдельного права");
        String reason = body.get("reason");
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleException("Сторно требует основания");
        }
        Long newId = jdbc.queryForObject("""
                INSERT INTO cash_transactions (uuid, occurred_on, direction, from_account_id, to_account_id,
                    amount, category_code, counterparty_id, project_id, circuit_code, comment,
                    reverses_id, created_by)
                SELECT :uuid, CURRENT_DATE,
                       CASE direction WHEN 'INCOME' THEN 'EXPENSE' WHEN 'EXPENSE' THEN 'INCOME'
                                      ELSE 'TRANSFER' END,
                       to_account_id, from_account_id, amount, category_code, counterparty_id,
                       project_id, circuit_code, 'Сторно операции #' || id || ': ' || :reason,
                       id, :author
                FROM cash_transactions WHERE id = :id
                RETURNING id
                """, Map.of("uuid", UUID.randomUUID(), "reason", reason,
                            "author", CurrentUser.name(), "id", id), Long.class);
        jdbc.update("UPDATE cash_transactions SET status = 'REVERSED' WHERE id = :id", Map.of("id", id));
        audit.action("CASH_TRANSACTION", id, "REVERSE", reason + " → операция #" + newId);
        return Map.of("reversal_id", newId);
    }

    @GetMapping("/transactions/{id}/allocations")
    public List<Map<String, Object>> allocations(@PathVariable Long id) {
        permissions.require("FINANCE_VIEW", null);
        return jdbc.queryForList("""
                SELECT ta.*, pe.formula AS payroll_formula, c.title AS compensation_title,
                       po.number AS order_number
                FROM transaction_allocations ta
                LEFT JOIN payroll_entries pe ON pe.id = ta.payroll_entry_id
                LEFT JOIN compensations c ON c.id = ta.compensation_id
                LEFT JOIN purchase_orders po ON po.id = ta.purchase_order_id
                WHERE ta.transaction_id = :t ORDER BY ta.id
                """, Map.of("t", id));
    }

    // ---- остатки и взаиморасчёты --------------------------------------------

    /** Остаток по каждому денежному счёту на дату — считается из проводок. */
    @GetMapping("/balances")
    public List<Map<String, Object>> balances(@RequestParam(required = false) LocalDate on) {
        permissions.require("FINANCE_VIEW", null);
        LocalDate date = on != null ? on : LocalDate.now();
        return jdbc.queryForList("""
                SELECT a.id, a.name, a.circuit_code, a.account_type,
                  COALESCE((SELECT SUM(t.amount) FROM cash_transactions t
                            WHERE t.to_account_id = a.id AND t.status <> 'REVERSED'
                              AND t.occurred_on <= :d), 0)
                  - COALESCE((SELECT SUM(t.amount) FROM cash_transactions t
                              WHERE t.from_account_id = a.id AND t.status <> 'REVERSED'
                                AND t.occurred_on <= :d), 0) AS balance
                FROM cash_accounts a
                WHERE a.is_active AND a.is_real
                ORDER BY a.circuit_code, a.sort_order, a.name
                """, Map.of("d", date));
    }

    /**
     * Взаимные обязательства между контурами: «Диполь ↔ Ковры ↔ личные деньги».
     * Временное финансирование не должно растворяться в общей кассе (US-L3).
     */
    @GetMapping("/circuit-debts")
    public List<Map<String, Object>> circuitDebts() {
        permissions.require("FINANCE_VIEW", null);
        return jdbc.queryForList("""
                SELECT d.debtor_code, dc.name AS debtor_name, d.creditor_code, cc.name AS creditor_name,
                       SUM(CASE WHEN d.settled_at IS NULL THEN d.amount ELSE 0 END) AS open_amount,
                       SUM(d.amount) AS total_amount
                FROM circuit_debts d
                JOIN money_circuits dc ON dc.code = d.debtor_code
                JOIN money_circuits cc ON cc.code = d.creditor_code
                GROUP BY d.debtor_code, dc.name, d.creditor_code, cc.name
                ORDER BY 1, 3
                """, Map.of());
    }

    @PostMapping("/circuit-debts")
    public Map<String, Object> createDebt(@RequestBody Map<String, Object> body) {
        permissions.require("FINANCE_EDIT", null);
        Long id = jdbc.queryForObject("""
                INSERT INTO circuit_debts (debtor_code, creditor_code, transaction_id, amount, comment)
                VALUES (:d, :c, :t, :a, :cm) RETURNING id
                """, new MapSqlParameterSource()
                        .addValue("d", body.get("debtor_code"))
                        .addValue("c", body.get("creditor_code"))
                        .addValue("t", num(body.get("transaction_id")))
                        .addValue("a", dec(body.get("amount")))
                        .addValue("cm", body.get("comment")), Long.class);
        return Map.of("id", id);
    }

    /** Расходы без основания — попадают в контроль качества данных (US-L4). */
    @GetMapping("/unjustified")
    public List<Map<String, Object>> unjustified(@RequestParam(required = false) LocalDate from) {
        permissions.require("FINANCE_VIEW", null);
        return jdbc.queryForList("""
                SELECT t.id, t.occurred_on, t.amount, t.comment, t.category_code
                FROM cash_transactions t
                WHERE t.direction = 'EXPENSE' AND t.status <> 'REVERSED'
                  AND t.occurred_on >= :f
                  AND NOT EXISTS (SELECT 1 FROM transaction_allocations a WHERE a.transaction_id = t.id)
                  AND (t.category_code IS NULL OR t.comment IS NULL OR t.comment = '')
                ORDER BY t.occurred_on DESC
                """, Map.of("f", from != null ? from : LocalDate.now().minusMonths(3)));
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
