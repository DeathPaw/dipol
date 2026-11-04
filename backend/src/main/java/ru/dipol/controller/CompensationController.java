package ru.dipol.controller;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.web.bind.annotation.*;
import ru.dipol.audit.CurrentUser;
import ru.dipol.exception.BusinessRuleException;
import ru.dipol.service.AuditService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Компенсации подтверждённых расходов сотрудника.
 *
 * <p>Ведутся отдельно от ФОТ и в отчётах не смешиваются с зарплатой, даже
 * если физически перечислены одним платежом (US-J4). Путь чека:
 * на руках → в офисе → у бухгалтера → компенсировано.
 */
@RestController
@RequestMapping("/api/compensations")
public class CompensationController {

    private static final List<String> FLOW =
            List.of("CHECK_ON_HANDS", "PASSED_TO_OFFICE", "PASSED_TO_ACCOUNTANT", "COMPENSATED");

    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService audit;

    public CompensationController(NamedParameterJdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) Long employeeId,
                                          @RequestParam(required = false) Long projectId,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) LocalDate from,
                                          @RequestParam(required = false) LocalDate to) {
        StringBuilder sql = new StringBuilder("""
                SELECT c.*, e.full_name AS employee_name, p.code AS project_code, m.name AS material_name
                FROM compensations c
                JOIN employees e ON e.id = c.employee_id
                LEFT JOIN projects p ON p.id = c.project_id
                LEFT JOIN materials m ON m.id = c.material_id
                WHERE 1=1
                """);
        Map<String, Object> params = new HashMap<>();
        if (employeeId != null) {
            sql.append(" AND c.employee_id = :e");
            params.put("e", employeeId);
        }
        if (projectId != null) {
            sql.append(" AND c.project_id = :p");
            params.put("p", projectId);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND c.status = :s");
            params.put("s", status);
        }
        if (from != null) {
            sql.append(" AND c.spent_on >= :f");
            params.put("f", from);
        }
        if (to != null) {
            sql.append(" AND c.spent_on <= :t");
            params.put("t", to);
        }
        sql.append(" ORDER BY c.spent_on DESC, c.id DESC");
        return jdbc.queryForList(sql.toString(), params);
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        var kh = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO compensations (employee_id, project_id, spent_on, amount, title,
                                           material_id, file_id, comment, created_by)
                VALUES (:e, :p, :d, :a, :t, :m, :f, :c, :author)
                """, new MapSqlParameterSource()
                        .addValue("e", num(body.get("employee_id")))
                        .addValue("p", num(body.get("project_id")))
                        .addValue("d", LocalDate.parse(String.valueOf(body.get("spent_on"))))
                        .addValue("a", dec(body.get("amount")))
                        .addValue("t", body.get("title"))
                        .addValue("m", num(body.get("material_id")))
                        .addValue("f", num(body.get("file_id")))
                        .addValue("c", body.get("comment"))
                        .addValue("author", CurrentUser.name()),
                kh, new String[]{"id"});
        Long id = ((Number) kh.getKeys().get("id")).longValue();
        audit.created("COMPENSATION", id);
        return Map.of("id", id);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        jdbc.update("""
                UPDATE compensations SET project_id = :p, spent_on = :d, amount = :a, title = :t,
                       material_id = :m, file_id = :f, comment = :c, updated_at = NOW()
                WHERE id = :id
                """, new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("p", num(body.get("project_id")))
                        .addValue("d", LocalDate.parse(String.valueOf(body.get("spent_on"))))
                        .addValue("a", dec(body.get("amount")))
                        .addValue("t", body.get("title"))
                        .addValue("m", num(body.get("material_id")))
                        .addValue("f", num(body.get("file_id")))
                        .addValue("c", body.get("comment")));
        audit.action("COMPENSATION", id, "UPDATE", null);
        return Map.of("status", "updated");
    }

    /** Переход статуса чека. Движение только вперёд по цепочке или откат с причиной. */
    @PostMapping("/{id}/status")
    public Map<String, Object> changeStatus(@PathVariable Long id, @RequestBody Map<String, String> body) {
        var current = jdbc.queryForMap("SELECT * FROM compensations WHERE id = :id", Map.of("id", id));
        String from = String.valueOf(current.get("status"));
        String to = body.get("status");
        if (to == null || !FLOW.contains(to)) {
            throw new BusinessRuleException("Неизвестный статус компенсации: " + to);
        }
        int fromIdx = FLOW.indexOf(from);
        int toIdx = FLOW.indexOf(to);
        if (toIdx < fromIdx && (body.get("reason") == null || body.get("reason").isBlank())) {
            throw new BusinessRuleException("Возврат статуса назад требует причины");
        }
        jdbc.update("UPDATE compensations SET status = :s, updated_at = NOW() WHERE id = :id",
                Map.of("s", to, "id", id));
        Map<String, Object> h = new HashMap<>();
        h.put("c", id);
        h.put("f", from);
        h.put("t", to);
        h.put("a", CurrentUser.name());
        jdbc.update("""
                INSERT INTO compensation_status_history (compensation_id, from_status, to_status, actor)
                VALUES (:c, :f, :t, :a)
                """, h);
        audit.status("COMPENSATION", id, from, to, body.get("reason"));
        return Map.of("status", to);
    }

    @GetMapping("/{id}/history")
    public List<Map<String, Object>> history(@PathVariable Long id) {
        return jdbc.queryForList(
                "SELECT * FROM compensation_status_history WHERE compensation_id = :c ORDER BY created_at",
                Map.of("c", id));
    }

    /** Сводка по статусам — сколько чеков и на какую сумму зависло на каждом шаге. */
    @GetMapping("/summary")
    public List<Map<String, Object>> summary(@RequestParam(required = false) LocalDate from,
                                             @RequestParam(required = false) LocalDate to) {
        Map<String, Object> p = new HashMap<>();
        p.put("f", from != null ? from : LocalDate.of(2000, 1, 1));
        p.put("t", to != null ? to : LocalDate.of(2100, 1, 1));
        return jdbc.queryForList("""
                SELECT status, COUNT(*) AS count, COALESCE(SUM(amount),0) AS total
                FROM compensations WHERE spent_on BETWEEN :f AND :t
                GROUP BY status ORDER BY status
                """, p);
    }

    private static Long num(Object v) {
        return v == null ? null : ((Number) v).longValue();
    }

    private static BigDecimal dec(Object v) {
        if (v == null || String.valueOf(v).isBlank()) return null;
        return new BigDecimal(String.valueOf(v));
    }
}
