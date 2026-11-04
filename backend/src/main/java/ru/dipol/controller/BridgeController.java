package ru.dipol.controller;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.*;
import ru.dipol.audit.CurrentUser;
import ru.dipol.service.BridgeService;
import ru.dipol.service.PermissionService;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Отчёт «Мост» и правила распределения официальной/кэш-части. */
@RestController
@RequestMapping("/api/bridge")
public class BridgeController {

    private final BridgeService service;
    private final PermissionService permissions;
    private final NamedParameterJdbcTemplate jdbc;

    public BridgeController(BridgeService service, PermissionService permissions,
                            NamedParameterJdbcTemplate jdbc) {
        this.service = service;
        this.permissions = permissions;
        this.jdbc = jdbc;
    }

    @GetMapping
    public Map<String, Object> build(@RequestParam LocalDate from,
                                     @RequestParam LocalDate to,
                                     @RequestParam(defaultValue = "true") boolean includeMaterials) {
        permissions.require("PAYROLL_VIEW", "Отчёт «Мост» доступен пользователям с правом на ФОТ");
        return service.build(from, to, includeMaterials);
    }

    /** Раскрытие строки отчёта до конкретных начислений. */
    @GetMapping("/details")
    public List<Map<String, Object>> details(@RequestParam LocalDate from, @RequestParam LocalDate to,
                                             @RequestParam(required = false) Long employeeId,
                                             @RequestParam(required = false) Long projectId) {
        permissions.require("PAYROLL_VIEW", null);
        StringBuilder sql = new StringBuilder("""
                SELECT pe.*, e.full_name AS employee_name, p.code AS project_code,
                       s.name AS sku_name, r.number AS request_number
                FROM payroll_entries pe
                JOIN employees e ON e.id = pe.employee_id
                JOIN projects p ON p.id = pe.project_id
                LEFT JOIN sku s ON s.id = pe.sku_id
                LEFT JOIN requests r ON r.id = pe.request_id
                WHERE pe.status <> 'REVERSED' AND pe.entry_date BETWEEN :f AND :t
                """);
        Map<String, Object> params = new HashMap<>();
        params.put("f", from);
        params.put("t", to);
        if (employeeId != null) {
            sql.append(" AND pe.employee_id = :e");
            params.put("e", employeeId);
        }
        if (projectId != null) {
            sql.append(" AND pe.project_id = :p");
            params.put("p", projectId);
        }
        sql.append(" ORDER BY pe.entry_date, pe.id");
        return jdbc.queryForList(sql.toString(), params);
    }

    @PostMapping("/save")
    public Map<String, Object> save(@RequestParam LocalDate from, @RequestParam LocalDate to,
                                    @RequestParam(defaultValue = "true") boolean includeMaterials) {
        permissions.require("PAYROLL_EDIT", null);
        var data = service.build(from, to, includeMaterials);
        Long id = service.save(from, to, data);
        return Map.of("report_id", id);
    }

    @GetMapping("/reports")
    public List<Map<String, Object>> reports() {
        permissions.require("PAYROLL_VIEW", null);
        return jdbc.queryForList(
                "SELECT id, period_from, period_to, created_by, created_at FROM bridge_reports"
                + " ORDER BY created_at DESC LIMIT 100", Map.of());
    }

    @GetMapping("/rules")
    public List<Map<String, Object>> rules() {
        permissions.require("PAYROLL_VIEW", null);
        return jdbc.queryForList("SELECT * FROM bridge_rules ORDER BY valid_from DESC", Map.of());
    }

    /** Новая версия правила «Моста». Прошлые отчёты остаются на своей версии. */
    @PostMapping("/rules")
    public Map<String, Object> createRule(@RequestBody Map<String, Object> body) {
        permissions.require("PAYROLL_EDIT", null);
        LocalDate from = LocalDate.parse(String.valueOf(body.get("valid_from")));
        Map<String, Object> p = new HashMap<>();
        p.put("c", body.getOrDefault("code", "BRIDGE"));
        p.put("n", body.getOrDefault("name", "Правило распределения белой и кэш-части"));
        p.put("j", body.get("params_json"));
        p.put("f", from);
        p.put("a", CurrentUser.name());
        jdbc.update("UPDATE bridge_rules SET valid_to = :f - INTERVAL '1 day' WHERE valid_to IS NULL", p);
        Long id = jdbc.queryForObject("""
                INSERT INTO bridge_rules (code, name, params_json, valid_from, author)
                VALUES (:c, :n, :j, :f, :a) RETURNING id
                """, p, Long.class);
        return Map.of("id", id);
    }
}
