package ru.dipol.controller;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.web.bind.annotation.*;
import ru.dipol.audit.CurrentUser;
import ru.dipol.exception.BusinessRuleException;
import ru.dipol.model.RequestItem;
import ru.dipol.repository.RequestRepository;
import ru.dipol.service.AuditService;
import ru.dipol.service.PayrollService;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Исполнение работ: старт/завершение, фото, проблемы, приёмка факта.
 *
 * <p>Принятый факт — единственный источник начислений ФОТ, себестоимости и
 * документов. Приёмка может подтвердить объём, скорректировать его с причиной
 * или вернуть работу на доработку (US-D6).
 */
@RestController
@RequestMapping("/api/execution")
public class ExecutionController {

    private final NamedParameterJdbcTemplate jdbc;
    private final RequestRepository requests;
    private final PayrollService payroll;
    private final AuditService audit;

    public ExecutionController(NamedParameterJdbcTemplate jdbc, RequestRepository requests,
                               PayrollService payroll, AuditService audit) {
        this.jdbc = jdbc;
        this.requests = requests;
        this.payroll = payroll;
        this.audit = audit;
    }

    // ---- сессии работы ------------------------------------------------------

    @GetMapping("/items/{itemId}/sessions")
    public List<Map<String, Object>> sessions(@PathVariable Long itemId) {
        return jdbc.queryForList("""
                SELECT ws.*, e.full_name AS employee_name
                FROM work_sessions ws JOIN employees e ON e.id = ws.employee_id
                WHERE ws.request_item_id = :i ORDER BY ws.started_at
                """, Map.of("i", itemId));
    }

    @PostMapping("/items/{itemId}/start")
    public Map<String, Object> start(@PathVariable Long itemId, @RequestBody Map<String, Object> body) {
        Long employeeId = num(body.get("employee_id"));
        if (employeeId == null) throw new BusinessRuleException("Не указан сотрудник");
        List<Map<String, Object>> open = jdbc.queryForList("""
                SELECT id FROM work_sessions
                WHERE request_item_id = :i AND employee_id = :e AND finished_at IS NULL
                """, Map.of("i", itemId, "e", employeeId));
        if (!open.isEmpty()) {
            throw new BusinessRuleException("У сотрудника уже есть незавершённая сессия по этой работе");
        }
        var kh = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO work_sessions (request_item_id, employee_id, trip_id, started_at)
                VALUES (:i, :e, :t, COALESCE(:s, NOW()))
                """, new MapSqlParameterSource().addValue("i", itemId).addValue("e", employeeId)
                     .addValue("t", num(body.get("trip_id")))
                     .addValue("s", dateTime(body.get("started_at"))),
                kh, new String[]{"id"});
        requests.setItemStatus(itemId, "IN_PROGRESS");
        RequestItem item = requests.item(itemId).orElseThrow();
        requests.addEvent(item.requestId(), "COMMENT", "Начата работа: " + item.skuName(),
                null, null, CurrentUser.name());
        return Map.of("session_id", ((Number) kh.getKeys().get("id")).longValue());
    }

    @PostMapping("/sessions/{sessionId}/finish")
    public Map<String, Object> finish(@PathVariable Long sessionId, @RequestBody Map<String, Object> body) {
        var session = jdbc.queryForMap("SELECT * FROM work_sessions WHERE id = :id", Map.of("id", sessionId));
        LocalDateTime finishedAt = dateTime(body.get("finished_at"));
        if (finishedAt == null) finishedAt = LocalDateTime.now();
        BigDecimal manHours = dec(body.get("man_hours"));
        if (manHours == null) {
            LocalDateTime started = ((java.sql.Timestamp) session.get("started_at")).toLocalDateTime();
            long minutes = Duration.between(started, finishedAt).toMinutes();
            manHours = BigDecimal.valueOf(Math.max(minutes, 0))
                    .divide(BigDecimal.valueOf(60), 2, java.math.RoundingMode.HALF_UP);
        }
        Map<String, Object> p = new HashMap<>();
        p.put("id", sessionId);
        p.put("f", finishedAt);
        p.put("h", manHours);
        p.put("th", dec(body.get("travel_hours")));
        p.put("c", body.get("comment"));
        jdbc.update("""
                UPDATE work_sessions SET finished_at = :f, man_hours = :h, travel_hours = :th, comment = :c
                WHERE id = :id
                """, p);

        Long itemId = ((Number) session.get("request_item_id")).longValue();
        // Часы позиции — сумма сессий: их вводят разные люди, и итог не должен
        // зависеть от того, кто закрыл свою сессию последним.
        jdbc.update("""
                UPDATE request_items SET man_hours = (
                    SELECT COALESCE(SUM(man_hours),0) FROM work_sessions WHERE request_item_id = :i
                ), status = 'ON_REVIEW', updated_at = NOW()
                WHERE id = :i
                """, Map.of("i", itemId));

        RequestItem item = requests.item(itemId).orElseThrow();
        requests.addEvent(item.requestId(), "COMMENT",
                "Завершена работа: " + item.skuName() + ", " + manHours + " чел·ч",
                null, null, CurrentUser.name());
        audit.action("REQUEST_ITEM", itemId, "FINISH", manHours + " чел·ч");
        return Map.of("session_id", sessionId, "man_hours", manHours, "item_status", "ON_REVIEW");
    }

    /** Правка времени прорабом — только с причиной; исходное остаётся в аудите. */
    @PutMapping("/sessions/{sessionId}")
    public Map<String, Object> correct(@PathVariable Long sessionId, @RequestBody Map<String, Object> body) {
        String reason = (String) body.get("correction_reason");
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleException("Корректировка времени требует причины");
        }
        var before = jdbc.queryForMap("SELECT * FROM work_sessions WHERE id = :id", Map.of("id", sessionId));
        Map<String, Object> p = new HashMap<>();
        p.put("id", sessionId);
        p.put("h", dec(body.get("man_hours")));
        p.put("th", dec(body.get("travel_hours")));
        p.put("r", reason);
        p.put("by", CurrentUser.name());
        jdbc.update("""
                UPDATE work_sessions SET man_hours = :h, travel_hours = :th,
                       correction_reason = :r, corrected_by = :by
                WHERE id = :id
                """, p);
        audit.changed("WORK_SESSION", sessionId, "man_hours", before.get("man_hours"), body.get("man_hours"));
        jdbc.update("""
                UPDATE request_items SET man_hours = (
                    SELECT COALESCE(SUM(man_hours),0) FROM work_sessions WHERE request_item_id = :i
                ) WHERE id = :i
                """, Map.of("i", before.get("request_item_id")));
        return Map.of("status", "corrected");
    }

    // ---- приёмка факта ------------------------------------------------------

    /**
     * Приёмка позиции. Из принятого объёма строятся начисления ФОТ —
     * поэтому пересчёт запускается здесь же (US-D6, US-J2).
     */
    @PostMapping("/items/{itemId}/accept")
    public Map<String, Object> accept(@PathVariable Long itemId, @RequestBody Map<String, Object> body) {
        RequestItem item = requests.item(itemId)
                .orElseThrow(() -> new ru.dipol.exception.EntityNotFoundException("Позиция", itemId));
        BigDecimal acceptedQty = dec(body.get("accepted_qty"));
        if (acceptedQty == null) acceptedQty = item.factQty();
        if (acceptedQty == null) throw new BusinessRuleException("Не указан принимаемый объём");

        String comment = (String) body.get("comment");
        if (item.factQty() != null && acceptedQty.compareTo(item.factQty()) != 0
                && (comment == null || comment.isBlank())) {
            throw new BusinessRuleException("Корректировка объёма при приёмке требует причины");
        }

        requests.acceptItem(itemId, acceptedQty, CurrentUser.name(), comment);
        audit.changed("REQUEST_ITEM", itemId, "accepted_qty", item.acceptedQty(), acceptedQty);
        requests.addEvent(item.requestId(), "QTY",
                "Принят объём по «" + item.skuName() + "»: " + acceptedQty + " " + item.unitCode(),
                null, null, CurrentUser.name());

        var accruals = payroll.accrueForItem(itemId);
        return Map.of("item", requests.item(itemId).orElseThrow(), "payroll_entries", accruals);
    }

    @PostMapping("/items/{itemId}/rework")
    public Map<String, Object> rework(@PathVariable Long itemId, @RequestBody Map<String, String> body) {
        String reason = body.get("reason");
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleException("Возврат на доработку требует причины");
        }
        RequestItem item = requests.item(itemId).orElseThrow();
        requests.setItemStatus(itemId, "REWORK");
        requests.addEvent(item.requestId(), "COMMENT",
                "Возврат на доработку «" + item.skuName() + "»: " + reason, null, null, CurrentUser.name());
        audit.action("REQUEST_ITEM", itemId, "REWORK", reason);
        return Map.of("status", "REWORK");
    }

    // ---- фото ---------------------------------------------------------------

    @GetMapping("/photos")
    public List<Map<String, Object>> photos(@RequestParam String entityType, @RequestParam Long entityId) {
        return jdbc.queryForList("""
                SELECT p.*, f.original_name, f.storage_key
                FROM photos p LEFT JOIN files f ON f.id = p.file_id
                WHERE p.entity_type = :t AND p.entity_id = :i ORDER BY p.created_at
                """, Map.of("t", entityType, "i", entityId));
    }

    @PostMapping("/photos")
    public Map<String, Object> addPhoto(@RequestBody Map<String, Object> body) {
        Map<String, Object> p = new HashMap<>();
        p.put("t", body.get("entity_type"));
        p.put("i", num(body.get("entity_id")));
        p.put("f", num(body.get("file_id")));
        p.put("u", body.get("external_url"));
        p.put("k", body.getOrDefault("photo_kind", "OTHER"));
        p.put("c", body.get("comment"));
        p.put("a", CurrentUser.name());
        if (p.get("f") == null && p.get("u") == null) {
            throw new BusinessRuleException("Нужен файл или ссылка на внешнее хранилище");
        }
        var kh = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO photos (entity_type, entity_id, file_id, external_url, photo_kind, comment, author)
                VALUES (:t, :i, :f, :u, :k, :c, :a)
                """, new MapSqlParameterSource(p), kh, new String[]{"id"});
        return Map.of("id", ((Number) kh.getKeys().get("id")).longValue());
    }

    @DeleteMapping("/photos/{id}")
    public Map<String, String> deletePhoto(@PathVariable Long id) {
        jdbc.update("DELETE FROM photos WHERE id = :id", Map.of("id", id));
        return Map.of("status", "deleted");
    }

    // ---- проблемы -----------------------------------------------------------

    @GetMapping("/problems")
    public List<Map<String, Object>> problems(@RequestParam(required = false) Long requestId,
                                              @RequestParam(required = false) String status) {
        StringBuilder sql = new StringBuilder("""
                SELECT pr.*, c.name AS category_name, e.full_name AS responsible_name,
                       r.number AS request_number
                FROM problems pr
                JOIN problem_categories c ON c.code = pr.category_code
                LEFT JOIN employees e ON e.id = pr.responsible_employee_id
                LEFT JOIN requests r ON r.id = pr.request_id
                WHERE 1=1
                """);
        Map<String, Object> p = new HashMap<>();
        if (requestId != null) {
            sql.append(" AND pr.request_id = :r");
            p.put("r", requestId);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND pr.status = :s");
            p.put("s", status);
        }
        sql.append(" ORDER BY pr.created_at DESC");
        return jdbc.queryForList(sql.toString(), p);
    }

    @PostMapping("/problems")
    public Map<String, Object> createProblem(@RequestBody Map<String, Object> body) {
        var kh = new GeneratedKeyHolder();
        var p = new MapSqlParameterSource()
                .addValue("r", num(body.get("request_id")))
                .addValue("i", num(body.get("request_item_id")))
                .addValue("c", body.get("category_code"))
                .addValue("d", body.get("description"))
                .addValue("e", num(body.get("responsible_employee_id")))
                .addValue("b", Boolean.TRUE.equals(body.get("blocks_work")))
                .addValue("a", CurrentUser.name());
        jdbc.update("""
                INSERT INTO problems (request_id, request_item_id, category_code, description,
                                      responsible_employee_id, blocks_work, created_by)
                VALUES (:r, :i, :c, :d, :e, :b, :a)
                """, p, kh, new String[]{"id"});
        Long id = ((Number) kh.getKeys().get("id")).longValue();
        Long requestId = num(body.get("request_id"));
        if (requestId != null) {
            requests.addEvent(requestId, "PROBLEM", String.valueOf(body.get("description")),
                    null, null, CurrentUser.name());
        }
        audit.created("PROBLEM", id);
        return Map.of("id", id);
    }

    @PostMapping("/problems/{id}/close")
    public Map<String, Object> closeProblem(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String resolution = (String) body.get("resolution");
        if (resolution == null || resolution.isBlank()) {
            throw new BusinessRuleException("Закрытие проблемы требует описания решения");
        }
        Map<String, Object> p = new HashMap<>();
        p.put("id", id);
        p.put("r", resolution);
        p.put("h", dec(body.get("extra_hours")));
        jdbc.update("""
                UPDATE problems SET status = 'RESOLVED', resolution = :r, extra_hours = :h, closed_at = NOW()
                WHERE id = :id
                """, p);
        audit.action("PROBLEM", id, "RESOLVE", resolution);
        return Map.of("status", "RESOLVED");
    }

    private static Long num(Object v) {
        return v == null ? null : ((Number) v).longValue();
    }

    private static BigDecimal dec(Object v) {
        if (v == null || String.valueOf(v).isBlank()) return null;
        return new BigDecimal(String.valueOf(v));
    }

    private static LocalDateTime dateTime(Object v) {
        if (v == null || String.valueOf(v).isBlank()) return null;
        return LocalDateTime.parse(String.valueOf(v));
    }
}
