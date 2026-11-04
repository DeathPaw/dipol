package ru.dipol.controller;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.web.bind.annotation.*;
import ru.dipol.audit.CurrentUser;
import ru.dipol.exception.BusinessRuleException;
import ru.dipol.repository.PayrollRepository;
import ru.dipol.service.AuditService;
import ru.dipol.service.PayrollService;
import ru.dipol.service.PermissionService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/payroll")
public class PayrollController {

    private final PayrollRepository repo;
    private final PayrollService service;
    private final PermissionService permissions;
    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService audit;

    public PayrollController(PayrollRepository repo, PayrollService service,
                             PermissionService permissions, NamedParameterJdbcTemplate jdbc,
                             AuditService audit) {
        this.repo = repo;
        this.service = service;
        this.permissions = permissions;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // ---- условия оплаты -----------------------------------------------------

    @GetMapping("/terms")
    public List<Map<String, Object>> terms(@RequestParam(required = false) Long employeeId,
                                           @RequestParam(required = false) Long projectId) {
        permissions.require("PAYROLL_VIEW", "Просмотр условий оплаты требует права «Просмотр ФОТ»");
        return repo.payTermsList(employeeId, projectId);
    }

    /**
     * Назначение схемы оплаты конкретному сотруднику в конкретном проекте.
     * Конфигурация проекта задаёт только ДОПУСТИМЫЕ схемы; какая из них
     * применяется — решается здесь (US-E4).
     */
    @PostMapping("/terms")
    public List<Map<String, Object>> createTerms(@RequestBody Map<String, Object> body) {
        permissions.require("PAYROLL_EDIT", "Изменение условий оплаты требует права «Начисления и условия оплаты»");
        Long employeeId = num(body.get("employee_id"));
        Long projectId = num(body.get("project_id"));
        String scheme = String.valueOf(body.get("scheme_code"));

        List<String> allowed = allowedSchemes(projectId);
        if (!allowed.isEmpty() && !allowed.contains(scheme)) {
            throw new BusinessRuleException("Схема «" + scheme
                    + "» не входит в допустимые для этого проекта: " + String.join(", ", allowed));
        }
        LocalDate from = body.get("valid_from") != null
                ? LocalDate.parse(String.valueOf(body.get("valid_from"))) : LocalDate.now();
        Long id = repo.createPayTerms(employeeId, projectId, scheme, from, dec(body.get("rate")),
                (String) body.get("formula_code"), (String) body.get("params_json"),
                CurrentUser.name(), (String) body.get("comment"));
        audit.action("PAY_TERMS", id, "CREATE",
                "сотрудник " + employeeId + ", проект " + projectId + ", схема " + scheme);
        return repo.payTermsList(employeeId, projectId);
    }

    private List<String> allowedSchemes(Long projectId) {
        List<String> raw = jdbc.queryForList("""
                SELECT c.allowed_pay_schemes FROM projects p
                JOIN project_configs c ON c.id = p.config_id WHERE p.id = :p
                """, Map.of("p", projectId), String.class);
        if (raw.isEmpty() || raw.get(0) == null) return List.of();
        return java.util.Arrays.stream(raw.get(0).split(",")).map(String::trim).toList();
    }

    // ---- тарифы -------------------------------------------------------------

    @GetMapping("/rates")
    public Map<String, Object> rates(@RequestParam(required = false) Long projectId,
                                     @RequestParam(required = false) Long skuId) {
        permissions.require("PAYROLL_VIEW", null);
        return Map.of("rates", repo.rates(projectId, skuId), "ambiguous", repo.ambiguousRates());
    }

    @PostMapping("/rates")
    public List<Map<String, Object>> createRate(@RequestBody Map<String, Object> body) {
        permissions.require("PAYROLL_EDIT", null);
        Map<String, Object> r = new HashMap<>(body);
        r.put("author", CurrentUser.name());
        Long id = repo.createRate(r);
        audit.created("PAYROLL_RATE", id);
        return repo.rates(num(body.get("project_id")), num(body.get("sku_id")));
    }

    /** Какое правило сработает для этих условий — проверка до расчёта (US-G5). */
    @GetMapping("/rates/resolve")
    public Map<String, Object> resolveRate(@RequestParam Long skuId,
                                           @RequestParam(required = false) Long projectId,
                                           @RequestParam(required = false) Long employeeId,
                                           @RequestParam(required = false) String roleCode,
                                           @RequestParam(required = false) LocalDate date) {
        var rate = repo.findRate(skuId, projectId, employeeId, roleCode,
                date != null ? date : LocalDate.now());
        return rate.map(r -> Map.<String, Object>of("found", true, "rate", r))
                   .orElse(Map.of("found", false));
    }

    // ---- начисления ---------------------------------------------------------

    @GetMapping("/entries")
    public List<Map<String, Object>> entries(@RequestParam(required = false) Long employeeId,
                                             @RequestParam(required = false) Long projectId,
                                             @RequestParam(required = false) LocalDate from,
                                             @RequestParam(required = false) LocalDate to,
                                             @RequestParam(required = false) String status) {
        permissions.require("PAYROLL_VIEW", null);
        return repo.entries(employeeId, projectId, from, to, status);
    }

    @PostMapping("/items/{itemId}/accrue")
    public List<PayrollService.Accrual> accrueItem(@PathVariable Long itemId) {
        permissions.require("PAYROLL_EDIT", null);
        return service.accrueForItem(itemId);
    }

    @PostMapping("/trips/{tripId}/accrue")
    public List<PayrollService.Accrual> accrueTrip(@PathVariable Long tripId,
                                                   @RequestBody Map<String, Object> body) {
        permissions.require("PAYROLL_EDIT", null);
        Long projectId = num(body.get("project_id"));
        LocalDate date = body.get("date") != null
                ? LocalDate.parse(String.valueOf(body.get("date"))) : LocalDate.now();
        if (projectId == null) throw new BusinessRuleException("Не указан проект для начисления за смену");
        return service.accrueForTrip(tripId, projectId, date);
    }

    @PostMapping("/entries/approve")
    @SuppressWarnings("unchecked")
    public Map<String, Object> approve(@RequestBody Map<String, Object> body) {
        permissions.require("PAYROLL_EDIT", null);
        List<Number> ids = (List<Number>) body.getOrDefault("ids", List.of());
        List<Long> list = ids.stream().map(Number::longValue).toList();
        repo.setEntryStatus(list, "APPROVED");
        list.forEach(id -> audit.action("PAYROLL_ENTRY", id, "APPROVE", null));
        return Map.of("approved", list.size());
    }

    @PostMapping("/entries/{id}/reverse")
    public Map<String, Object> reverse(@PathVariable Long id, @RequestBody Map<String, String> body) {
        permissions.require("PAYROLL_EDIT", null);
        service.reverse(id, body.get("reason"));
        return Map.of("status", "reversed");
    }

    /** Ручное начисление — всегда с основанием и автором. */
    @PostMapping("/entries/manual")
    public Map<String, Object> manual(@RequestBody Map<String, Object> body) {
        permissions.require("PAYROLL_EDIT", null);
        String reason = (String) body.get("reason");
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleException("Ручное начисление требует основания");
        }
        LocalDate date = LocalDate.parse(String.valueOf(body.get("entry_date")));
        Map<String, Object> e = new HashMap<>();
        e.put("employee_id", num(body.get("employee_id")));
        e.put("project_id", num(body.get("project_id")));
        e.put("basis_type", "MANUAL");
        e.put("period_month", date.withDayOfMonth(1));
        e.put("entry_date", date);
        e.put("amount", dec(body.get("amount")));
        e.put("formula", "Ручное начисление");
        e.put("is_manual", true);
        e.put("reason", reason);
        e.put("created_by", CurrentUser.name());
        Long id = repo.insertEntry(e);
        audit.action("PAYROLL_ENTRY", id, "MANUAL", reason);
        return Map.of("id", id);
    }

    // ---- премии, штрафы, авансы, прорабские ---------------------------------

    @GetMapping("/adjustments")
    public List<Map<String, Object>> adjustments(@RequestParam(required = false) Long employeeId,
                                                 @RequestParam(required = false) LocalDate from,
                                                 @RequestParam(required = false) LocalDate to) {
        permissions.require("PAYROLL_VIEW", null);
        StringBuilder sql = new StringBuilder("""
                SELECT a.*, e.full_name AS employee_name, p.code AS project_code
                FROM payroll_adjustments a
                JOIN employees e ON e.id = a.employee_id
                LEFT JOIN projects p ON p.id = a.project_id
                WHERE 1=1
                """);
        Map<String, Object> params = new HashMap<>();
        if (employeeId != null) {
            sql.append(" AND a.employee_id = :e");
            params.put("e", employeeId);
        }
        if (from != null) {
            sql.append(" AND a.adj_date >= :f");
            params.put("f", from);
        }
        if (to != null) {
            sql.append(" AND a.adj_date <= :t");
            params.put("t", to);
        }
        sql.append(" ORDER BY a.adj_date DESC");
        return jdbc.queryForList(sql.toString(), params);
    }

    @PostMapping("/adjustments")
    public Map<String, Object> createAdjustment(@RequestBody Map<String, Object> body) {
        permissions.require("PAYROLL_EDIT", null);
        String reason = (String) body.get("reason");
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleException("Премия, штраф или аванс требуют основания");
        }
        LocalDate date = LocalDate.parse(String.valueOf(body.get("adj_date")));
        var kh = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO payroll_adjustments (employee_id, project_id, period_month, adj_date,
                                                 kind, amount, reason, author)
                VALUES (:e, :p, :m, :d, :k, :a, :r, :author)
                """, new MapSqlParameterSource()
                        .addValue("e", num(body.get("employee_id")))
                        .addValue("p", num(body.get("project_id")))
                        .addValue("m", date.withDayOfMonth(1))
                        .addValue("d", date)
                        .addValue("k", body.get("kind"))
                        .addValue("a", dec(body.get("amount")))
                        .addValue("r", reason)
                        .addValue("author", CurrentUser.name()),
                kh, new String[]{"id"});
        Long id = ((Number) kh.getKeys().get("id")).longValue();
        audit.action("PAYROLL_ADJUSTMENT", id, "CREATE", body.get("kind") + ": " + reason);
        return Map.of("id", id);
    }

    // ---- взаиморасчёты ------------------------------------------------------

    @GetMapping("/settlements")
    public List<Map<String, Object>> settlements(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        permissions.require("PAYROLL_VIEW", null);
        return repo.settlements(from, to);
    }

    private static Long num(Object v) {
        return v == null ? null : ((Number) v).longValue();
    }

    private static BigDecimal dec(Object v) {
        if (v == null || String.valueOf(v).isBlank()) return null;
        return new BigDecimal(String.valueOf(v));
    }
}
