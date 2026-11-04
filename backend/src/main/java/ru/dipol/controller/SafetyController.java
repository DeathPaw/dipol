package ru.dipol.controller;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.web.bind.annotation.*;
import ru.dipol.audit.CurrentUser;
import ru.dipol.exception.BusinessRuleException;
import ru.dipol.service.AuditService;
import ru.dipol.service.PermissionService;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Техника безопасности: документы, наряды-допуски, журналы и подтверждения.
 *
 * <p>Оформленный документ не удаляется — исправления идут новой версией или
 * корректировкой (US-P6). Работник видит только свои документы и подтверждает
 * ознакомление в мобильном кабинете (US-P5).
 */
@RestController
@RequestMapping("/api/safety")
public class SafetyController {

    private final NamedParameterJdbcTemplate jdbc;
    private final PermissionService permissions;
    private final AuditService audit;

    public SafetyController(NamedParameterJdbcTemplate jdbc, PermissionService permissions,
                            AuditService audit) {
        this.jdbc = jdbc;
        this.permissions = permissions;
        this.audit = audit;
    }

    // ---- документы ----------------------------------------------------------

    @GetMapping("/documents")
    public List<Map<String, Object>> documents(@RequestParam(required = false) String typeCode,
                                               @RequestParam(required = false) Long employeeId,
                                               @RequestParam(required = false) String q) {
        StringBuilder sql = new StringBuilder("""
                SELECT d.*, t.name AS type_name, e.full_name AS employee_name, f.original_name AS file_name
                FROM safety_documents d
                JOIN safety_doc_types t ON t.code = d.type_code
                LEFT JOIN employees e ON e.id = d.employee_id
                LEFT JOIN files f ON f.id = d.file_id
                WHERE 1=1
                """);
        Map<String, Object> p = new HashMap<>();
        if (typeCode != null && !typeCode.isBlank()) {
            sql.append(" AND d.type_code = :t");
            p.put("t", typeCode);
        }
        if (employeeId != null) {
            sql.append(" AND d.employee_id = :e");
            p.put("e", employeeId);
        }
        if (q != null && !q.isBlank()) {
            sql.append(" AND (d.title ILIKE :q OR d.doc_number ILIKE :q)");
            p.put("q", "%" + q.trim() + "%");
        }
        sql.append(" ORDER BY d.valid_from DESC NULLS LAST, d.id DESC");
        return jdbc.queryForList(sql.toString(), p);
    }

    @PostMapping("/documents")
    public Map<String, Object> createDocument(@RequestBody Map<String, Object> body) {
        permissions.require("SAFETY_EDIT", "Раздел ТБ требует соответствующего права");
        var kh = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO safety_documents (type_code, title, doc_number, version, valid_from, valid_to,
                    file_id, employee_id, project_id, vehicle_id, sku_id, note, author)
                VALUES (:t, :ti, :n, :v, :from, :to, :f, :e, :p, :veh, :sku, :note, :a)
                """, new MapSqlParameterSource()
                        .addValue("t", body.get("type_code"))
                        .addValue("ti", body.get("title"))
                        .addValue("n", body.get("doc_number"))
                        .addValue("v", body.getOrDefault("version", 1))
                        .addValue("from", date(body.get("valid_from")))
                        .addValue("to", date(body.get("valid_to")))
                        .addValue("f", num(body.get("file_id")))
                        .addValue("e", num(body.get("employee_id")))
                        .addValue("p", num(body.get("project_id")))
                        .addValue("veh", num(body.get("vehicle_id")))
                        .addValue("sku", num(body.get("sku_id")))
                        .addValue("note", body.get("note"))
                        .addValue("a", CurrentUser.name()),
                kh, new String[]{"id"});
        Long id = ((Number) kh.getKeys().get("id")).longValue();
        audit.created("SAFETY_DOCUMENT", id);
        return Map.of("id", id);
    }

    /** Документы с истекающим сроком — попадают в дашборд ТБ. */
    @GetMapping("/documents/expiring")
    public List<Map<String, Object>> expiring(@RequestParam(defaultValue = "30") int days) {
        return jdbc.queryForList("""
                SELECT d.*, e.full_name AS employee_name FROM safety_documents d
                LEFT JOIN employees e ON e.id = d.employee_id
                WHERE d.valid_to IS NOT NULL AND d.valid_to <= CURRENT_DATE + (:d || ' days')::interval
                ORDER BY d.valid_to
                """, Map.of("d", days));
    }

    // ---- наряды-допуски -----------------------------------------------------

    @GetMapping("/permits")
    public List<Map<String, Object>> permits(@RequestParam(required = false) LocalDate from,
                                             @RequestParam(required = false) LocalDate to,
                                             @RequestParam(required = false) String status) {
        StringBuilder sql = new StringBuilder("""
                SELECT p.*, b.address AS building_address, pr.code AS project_code,
                       (SELECT COUNT(*) FROM work_permit_employees we WHERE we.permit_id = p.id) AS employees_count,
                       (SELECT COUNT(*) FROM work_permit_employees we
                        WHERE we.permit_id = p.id AND we.acknowledged_at IS NOT NULL) AS acknowledged_count
                FROM work_permits p
                LEFT JOIN buildings b ON b.id = p.building_id
                LEFT JOIN projects pr ON pr.id = p.project_id
                WHERE 1=1
                """);
        Map<String, Object> pm = new HashMap<>();
        if (from != null) {
            sql.append(" AND p.permit_date >= :f");
            pm.put("f", from);
        }
        if (to != null) {
            sql.append(" AND p.permit_date <= :t");
            pm.put("t", to);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND p.status = :s");
            pm.put("s", status);
        }
        sql.append(" ORDER BY p.permit_date DESC");
        return jdbc.queryForList(sql.toString(), pm);
    }

    @GetMapping("/permits/{id}")
    public Map<String, Object> permit(@PathVariable Long id) {
        return Map.of(
                "permit", jdbc.queryForMap("SELECT * FROM work_permits WHERE id = :id", Map.of("id", id)),
                "employees", jdbc.queryForList("""
                        SELECT we.*, e.full_name FROM work_permit_employees we
                        JOIN employees e ON e.id = we.employee_id WHERE we.permit_id = :id
                        ORDER BY e.full_name
                        """, Map.of("id", id)),
                "requests", jdbc.queryForList("""
                        SELECT r.id, r.number, b.address FROM work_permit_requests wr
                        JOIN requests r ON r.id = wr.request_id
                        JOIN buildings b ON b.id = r.building_id
                        WHERE wr.permit_id = :id
                        """, Map.of("id", id))
        );
    }

    /**
     * Наряд формируется из данных планирования: адрес, заявки и состав
     * сотрудников подставляются из выезда, повторно их не вводят (US-P2).
     */
    @PostMapping("/permits")
    @SuppressWarnings("unchecked")
    public Map<String, Object> createPermit(@RequestBody Map<String, Object> body) {
        permissions.require("SAFETY_EDIT", null);
        Long tripId = num(body.get("trip_id"));
        var kh = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO work_permits (number, permit_date, valid_to, building_id, project_id, trip_id,
                    template_id, template_version, work_kinds, issued_by, note)
                VALUES (:n, :d, :vt, :b, :p, :t, :tpl, :tv, :wk, :by, :note)
                """, new MapSqlParameterSource()
                        .addValue("n", body.get("number"))
                        .addValue("d", date(body.get("permit_date")))
                        .addValue("vt", date(body.get("valid_to")))
                        .addValue("b", num(body.get("building_id")))
                        .addValue("p", num(body.get("project_id")))
                        .addValue("t", tripId)
                        .addValue("tpl", num(body.get("template_id")))
                        .addValue("tv", body.get("template_version"))
                        .addValue("wk", body.get("work_kinds"))
                        .addValue("by", CurrentUser.name())
                        .addValue("note", body.get("note")),
                kh, new String[]{"id"});
        Long id = ((Number) kh.getKeys().get("id")).longValue();

        if (tripId != null) {
            jdbc.update("""
                    INSERT INTO work_permit_employees (permit_id, employee_id)
                    SELECT :p, employee_id FROM trip_members WHERE trip_id = :t
                    ON CONFLICT DO NOTHING
                    """, Map.of("p", id, "t", tripId));
            jdbc.update("""
                    INSERT INTO work_permit_requests (permit_id, request_id)
                    SELECT :p, request_id FROM trip_requests WHERE trip_id = :t
                    ON CONFLICT DO NOTHING
                    """, Map.of("p", id, "t", tripId));
        }
        List<Number> employeeIds = (List<Number>) body.getOrDefault("employee_ids", List.of());
        for (var e : employeeIds) {
            jdbc.update("""
                    INSERT INTO work_permit_employees (permit_id, employee_id) VALUES (:p, :e)
                    ON CONFLICT DO NOTHING
                    """, Map.of("p", id, "e", e.longValue()));
        }
        audit.created("WORK_PERMIT", id);
        return permit(id);
    }

    @PostMapping("/permits/{id}/status")
    public Map<String, Object> permitStatus(@PathVariable Long id, @RequestBody Map<String, String> body) {
        permissions.require("SAFETY_EDIT", null);
        String status = body.get("status");
        if ("CANCELLED".equals(status) && (body.get("reason") == null || body.get("reason").isBlank())) {
            throw new BusinessRuleException("Аннулирование наряда требует причины");
        }
        jdbc.update("""
                UPDATE work_permits SET status = :s,
                    issued_at = CASE WHEN :s = 'ISSUED' THEN NOW() ELSE issued_at END,
                    closed_at = CASE WHEN :s = 'CLOSED' THEN NOW() ELSE closed_at END
                WHERE id = :id
                """, Map.of("s", status, "id", id));
        audit.status("WORK_PERMIT", id, null, status, body.get("reason"));
        return Map.of("status", status);
    }

    /** Подтверждение ознакомления работником — фиксирует пользователя и время. */
    @PostMapping("/permits/{id}/acknowledge")
    public Map<String, Object> acknowledge(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long employeeId = num(body.get("employee_id"));
        jdbc.update("""
                UPDATE work_permit_employees SET acknowledged_at = NOW(), acknowledged_by = :by
                WHERE permit_id = :p AND employee_id = :e
                """, Map.of("p", id, "e", employeeId, "by", CurrentUser.name()));
        audit.action("WORK_PERMIT", id, "ACKNOWLEDGE", "сотрудник id=" + employeeId);
        return Map.of("status", "acknowledged");
    }

    /** Сотрудники в наряде без подтверждения — прораб должен узнать до выезда. */
    @GetMapping("/permits/{id}/pending")
    public List<Map<String, Object>> pending(@PathVariable Long id) {
        return jdbc.queryForList("""
                SELECT e.id, e.full_name FROM work_permit_employees we
                JOIN employees e ON e.id = we.employee_id
                WHERE we.permit_id = :p AND we.acknowledged_at IS NULL
                """, Map.of("p", id));
    }

    // ---- журналы ------------------------------------------------------------

    @GetMapping("/journals")
    public List<Map<String, Object>> journals() {
        return jdbc.queryForList("SELECT * FROM safety_journals WHERE is_active ORDER BY name", Map.of());
    }

    @GetMapping("/journals/{id}/entries")
    public List<Map<String, Object>> entries(@PathVariable Long id,
                                             @RequestParam(required = false) LocalDate from,
                                             @RequestParam(required = false) LocalDate to) {
        StringBuilder sql = new StringBuilder("""
                SELECT je.*, e.full_name AS employee_name, r.full_name AS responsible_name
                FROM safety_journal_entries je
                LEFT JOIN employees e ON e.id = je.employee_id
                LEFT JOIN employees r ON r.id = je.responsible_employee_id
                WHERE je.journal_id = :j
                """);
        Map<String, Object> p = new HashMap<>();
        p.put("j", id);
        if (from != null) {
            sql.append(" AND je.entry_date >= :f");
            p.put("f", from);
        }
        if (to != null) {
            sql.append(" AND je.entry_date <= :t");
            p.put("t", to);
        }
        sql.append(" ORDER BY je.entry_date DESC, je.id DESC");
        return jdbc.queryForList(sql.toString(), p);
    }

    @PostMapping("/journals/{id}/entries")
    public Map<String, Object> addEntry(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        permissions.require("SAFETY_EDIT", null);
        Long entryId = jdbc.queryForObject("""
                INSERT INTO safety_journal_entries (journal_id, entry_date, employee_id, event,
                    responsible_employee_id, file_id, note, created_by)
                VALUES (:j, :d, :e, :ev, :r, :f, :n, :a) RETURNING id
                """, new MapSqlParameterSource()
                        .addValue("j", id)
                        .addValue("d", date(body.get("entry_date")))
                        .addValue("e", num(body.get("employee_id")))
                        .addValue("ev", body.get("event"))
                        .addValue("r", num(body.get("responsible_employee_id")))
                        .addValue("f", num(body.get("file_id")))
                        .addValue("n", body.get("note"))
                        .addValue("a", CurrentUser.name()), Long.class);
        return Map.of("id", entryId);
    }

    /** Документы и наряды конкретного работника — для мобильного кабинета. */
    @GetMapping("/for-employee/{employeeId}")
    public Map<String, Object> forEmployee(@PathVariable Long employeeId) {
        return Map.of(
                "documents", jdbc.queryForList("""
                        SELECT d.*, t.name AS type_name FROM safety_documents d
                        JOIN safety_doc_types t ON t.code = d.type_code
                        WHERE d.employee_id = :e
                          AND (d.valid_to IS NULL OR d.valid_to >= CURRENT_DATE)
                        ORDER BY d.valid_from DESC NULLS LAST
                        """, Map.of("e", employeeId)),
                "permits", jdbc.queryForList("""
                        SELECT p.id, p.number, p.permit_date, p.work_kinds, p.status,
                               we.acknowledged_at, b.address
                        FROM work_permit_employees we
                        JOIN work_permits p ON p.id = we.permit_id
                        LEFT JOIN buildings b ON b.id = p.building_id
                        WHERE we.employee_id = :e AND p.status IN ('ISSUED','DRAFT')
                        ORDER BY p.permit_date DESC
                        """, Map.of("e", employeeId))
        );
    }

    private static Long num(Object v) {
        return v == null ? null : ((Number) v).longValue();
    }

    private static LocalDate date(Object v) {
        if (v == null || String.valueOf(v).isBlank()) return null;
        return LocalDate.parse(String.valueOf(v));
    }
}
