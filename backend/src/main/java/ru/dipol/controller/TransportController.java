package ru.dipol.controller;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.web.bind.annotation.*;
import ru.dipol.audit.CurrentUser;
import ru.dipol.service.AuditService;
import ru.dipol.service.PermissionService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Автомобили, ТО и страховки, расходы, собственное оборудование. */
@RestController
@RequestMapping("/api/transport")
public class TransportController {

    private final NamedParameterJdbcTemplate jdbc;
    private final PermissionService permissions;
    private final AuditService audit;

    public TransportController(NamedParameterJdbcTemplate jdbc, PermissionService permissions,
                               AuditService audit) {
        this.jdbc = jdbc;
        this.permissions = permissions;
        this.audit = audit;
    }

    // ---- автомобили ---------------------------------------------------------

    @GetMapping("/vehicles")
    public List<Map<String, Object>> vehicles(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return jdbc.queryForList("""
                SELECT v.*, e.full_name AS assigned_name,
                  (SELECT MAX(m.valid_to) FROM vehicle_maintenance m
                   WHERE m.vehicle_id = v.id AND m.kind = 'INSURANCE') AS insurance_valid_to,
                  (SELECT MAX(m.occurred_on) FROM vehicle_maintenance m
                   WHERE m.vehicle_id = v.id AND m.kind = 'SERVICE') AS last_service_on
                FROM vehicles v LEFT JOIN employees e ON e.id = v.assigned_employee_id
                """ + (includeInactive ? "" : " WHERE v.is_active") + " ORDER BY v.plate", Map.of());
    }

    @GetMapping("/vehicles/{id}")
    public Map<String, Object> vehicle(@PathVariable Long id) {
        return Map.of(
                "vehicle", jdbc.queryForMap("SELECT * FROM vehicles WHERE id = :id", Map.of("id", id)),
                "maintenance", jdbc.queryForList(
                        "SELECT * FROM vehicle_maintenance WHERE vehicle_id = :id ORDER BY occurred_on DESC",
                        Map.of("id", id)),
                "expenses", jdbc.queryForList("""
                        SELECT e.*, c.name AS category_name FROM vehicle_expenses e
                        JOIN vehicle_expense_categories c ON c.code = e.category_code
                        WHERE e.vehicle_id = :id ORDER BY e.spent_on DESC LIMIT 200
                        """, Map.of("id", id)),
                "trips", jdbc.queryForList("""
                        SELECT id, trip_date, status, mileage_total FROM trips
                        WHERE vehicle_id = :id ORDER BY trip_date DESC LIMIT 100
                        """, Map.of("id", id))
        );
    }

    @PostMapping("/vehicles")
    public Map<String, Object> createVehicle(@RequestBody Map<String, Object> body) {
        permissions.require("TRANSPORT_EDIT", null);
        var kh = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO vehicles (brand, model, plate, status, assigned_employee_id, mileage, note)
                VALUES (:b, :m, :p, :s, :e, :mi, :n)
                """, new MapSqlParameterSource()
                        .addValue("b", body.get("brand")).addValue("m", body.get("model"))
                        .addValue("p", body.get("plate"))
                        .addValue("s", body.getOrDefault("status", "ACTIVE"))
                        .addValue("e", num(body.get("assigned_employee_id")))
                        .addValue("mi", body.get("mileage")).addValue("n", body.get("note")),
                kh, new String[]{"id"});
        Long id = ((Number) kh.getKeys().get("id")).longValue();
        audit.created("VEHICLE", id);
        return Map.of("id", id);
    }

    @PutMapping("/vehicles/{id}")
    public Map<String, Object> updateVehicle(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        permissions.require("TRANSPORT_EDIT", null);
        jdbc.update("""
                UPDATE vehicles SET brand = :b, model = :m, plate = :p, status = :s,
                       assigned_employee_id = :e, mileage = :mi, note = :n, is_active = :a, updated_at = NOW()
                WHERE id = :id
                """, new MapSqlParameterSource()
                        .addValue("id", id).addValue("b", body.get("brand")).addValue("m", body.get("model"))
                        .addValue("p", body.get("plate")).addValue("s", body.getOrDefault("status", "ACTIVE"))
                        .addValue("e", num(body.get("assigned_employee_id")))
                        .addValue("mi", body.get("mileage")).addValue("n", body.get("note"))
                        .addValue("a", !Boolean.FALSE.equals(body.get("is_active"))));
        audit.action("VEHICLE", id, "UPDATE", null);
        return Map.of("status", "updated");
    }

    @PostMapping("/vehicles/{id}/maintenance")
    public Map<String, Object> addMaintenance(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        permissions.require("TRANSPORT_EDIT", null);
        Long mid = jdbc.queryForObject("""
                INSERT INTO vehicle_maintenance (vehicle_id, kind, occurred_on, mileage, works, cost,
                                                 provider, valid_from, valid_to, note)
                VALUES (:v, :k, :d, :mi, :w, :c, :p, :vf, :vt, :n) RETURNING id
                """, new MapSqlParameterSource()
                        .addValue("v", id).addValue("k", body.get("kind"))
                        .addValue("d", date(body.get("occurred_on"))).addValue("mi", body.get("mileage"))
                        .addValue("w", body.get("works")).addValue("c", dec(body.get("cost")))
                        .addValue("p", body.get("provider"))
                        .addValue("vf", date(body.get("valid_from"))).addValue("vt", date(body.get("valid_to")))
                        .addValue("n", body.get("note")), Long.class);
        return Map.of("id", mid);
    }

    @PostMapping("/vehicles/{id}/expenses")
    public Map<String, Object> addExpense(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        permissions.require("TRANSPORT_EDIT", null);
        Long eid = jdbc.queryForObject("""
                INSERT INTO vehicle_expenses (vehicle_id, category_code, spent_on, amount, project_id,
                                              trip_id, file_id, note, created_by)
                VALUES (:v, :c, :d, :a, :p, :t, :f, :n, :author) RETURNING id
                """, new MapSqlParameterSource()
                        .addValue("v", id).addValue("c", body.get("category_code"))
                        .addValue("d", date(body.get("spent_on"))).addValue("a", dec(body.get("amount")))
                        .addValue("p", num(body.get("project_id"))).addValue("t", num(body.get("trip_id")))
                        .addValue("f", num(body.get("file_id"))).addValue("n", body.get("note"))
                        .addValue("author", CurrentUser.name()), Long.class);
        return Map.of("id", eid);
    }

    /** Просроченные ТО и страховки — их подсвечивают до выпуска машины (US-Q3). */
    @GetMapping("/vehicles/alerts")
    public List<Map<String, Object>> alerts(@RequestParam(defaultValue = "30") int days) {
        return jdbc.queryForList("""
                SELECT v.id, v.plate, m.kind, m.valid_to,
                       (m.valid_to - CURRENT_DATE) AS days_left
                FROM vehicle_maintenance m JOIN vehicles v ON v.id = m.vehicle_id
                WHERE m.valid_to IS NOT NULL AND v.is_active
                  AND m.valid_to <= CURRENT_DATE + (:d || ' days')::interval
                  AND m.valid_to = (SELECT MAX(m2.valid_to) FROM vehicle_maintenance m2
                                    WHERE m2.vehicle_id = v.id AND m2.kind = m.kind)
                ORDER BY m.valid_to
                """, Map.of("d", days));
    }

    /** Расходы по автомобилю за период — отдельно от прямого километража заявок. */
    @GetMapping("/vehicles/{id}/expense-report")
    public List<Map<String, Object>> expenseReport(@PathVariable Long id,
                                                   @RequestParam LocalDate from,
                                                   @RequestParam LocalDate to) {
        return jdbc.queryForList("""
                SELECT c.name AS category_name, COUNT(*) AS count, COALESCE(SUM(e.amount),0) AS total
                FROM vehicle_expenses e JOIN vehicle_expense_categories c ON c.code = e.category_code
                WHERE e.vehicle_id = :v AND e.spent_on BETWEEN :f AND :t
                GROUP BY c.name ORDER BY total DESC
                """, Map.of("v", id, "f", from, "t", to));
    }

    // ---- оборудование -------------------------------------------------------

    @GetMapping("/equipment")
    public List<Map<String, Object>> equipment() {
        return jdbc.queryForList("""
                SELECT eq.*, e.full_name AS assigned_name FROM equipment eq
                LEFT JOIN employees e ON e.id = eq.assigned_employee_id
                ORDER BY eq.name
                """, Map.of());
    }

    @PostMapping("/equipment")
    public Map<String, Object> createEquipment(@RequestBody Map<String, Object> body) {
        permissions.require("TRANSPORT_EDIT", null);
        Long id = jdbc.queryForObject("""
                INSERT INTO equipment (inventory_no, name, equipment_type, assigned_employee_id,
                                       purchase_cost, purchased_on, note)
                VALUES (:i, :n, :t, :e, :c, :d, :note) RETURNING id
                """, new MapSqlParameterSource()
                        .addValue("i", body.get("inventory_no")).addValue("n", body.get("name"))
                        .addValue("t", body.get("equipment_type"))
                        .addValue("e", num(body.get("assigned_employee_id")))
                        .addValue("c", dec(body.get("purchase_cost")))
                        .addValue("d", date(body.get("purchased_on")))
                        .addValue("note", body.get("note")), Long.class);
        return Map.of("id", id);
    }

    @GetMapping("/depreciation-norms")
    public List<Map<String, Object>> norms() {
        return jdbc.queryForList("""
                SELECT d.*, e.name AS equipment_name, c.name AS config_name, s.name AS sku_name
                FROM depreciation_norms d
                LEFT JOIN equipment e ON e.id = d.equipment_id
                LEFT JOIN project_configs c ON c.id = d.project_config_id
                LEFT JOIN sku s ON s.id = d.sku_id
                ORDER BY d.valid_from DESC
                """, Map.of());
    }

    @PostMapping("/depreciation-norms")
    public Map<String, Object> createNorm(@RequestBody Map<String, Object> body) {
        permissions.require("TRANSPORT_EDIT", null);
        Long id = jdbc.queryForObject("""
                INSERT INTO depreciation_norms (equipment_id, project_config_id, sku_id, basis, amount,
                                                valid_from, valid_to, author)
                VALUES (:e, :c, :s, :b, :a, :f, :t, :author) RETURNING id
                """, new MapSqlParameterSource()
                        .addValue("e", num(body.get("equipment_id")))
                        .addValue("c", num(body.get("project_config_id")))
                        .addValue("s", num(body.get("sku_id")))
                        .addValue("b", body.getOrDefault("basis", "SHIFT"))
                        .addValue("a", dec(body.get("amount")))
                        .addValue("f", date(body.get("valid_from")))
                        .addValue("t", date(body.get("valid_to")))
                        .addValue("author", CurrentUser.name()), Long.class);
        return Map.of("id", id);
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
