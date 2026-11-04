package ru.dipol.controller;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.web.bind.annotation.*;
import ru.dipol.audit.CurrentUser;
import ru.dipol.exception.BusinessRuleException;
import ru.dipol.service.AuditService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Смены спецтехники: ПУХТО, кран, вышка, манипулятор.
 *
 * <p>Это самостоятельная сущность, а не атрибут выезда (US-C4): одна смена
 * обслуживает несколько близких адресов и не обязана совпадать с выездом
 * бригады. Стоимость распределяется по адресам суммой, процентом, часами
 * или рейсами; нераспределённый остаток виден всегда.
 */
@RestController
@RequestMapping("/api/equipment-shifts")
public class EquipmentShiftController {

    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService audit;

    public EquipmentShiftController(NamedParameterJdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) LocalDate from,
                                          @RequestParam(required = false) LocalDate to,
                                          @RequestParam(required = false) String typeCode,
                                          @RequestParam(required = false) Long projectId) {
        StringBuilder sql = new StringBuilder("""
                SELECT s.*, t.name AS type_name, sup.name AS supplier_display_name,
                       COALESCE((SELECT SUM(a.amount) FROM equipment_shift_allocations a
                                 WHERE a.shift_id = s.id), 0) AS allocated_amount
                FROM equipment_shifts s
                JOIN special_equipment_types t ON t.code = s.type_code
                LEFT JOIN suppliers sup ON sup.id = s.supplier_id
                WHERE 1=1
                """);
        Map<String, Object> p = new HashMap<>();
        if (from != null) {
            sql.append(" AND s.date_from >= :from");
            p.put("from", from.atStartOfDay());
        }
        if (to != null) {
            sql.append(" AND s.date_from < :to");
            p.put("to", to.plusDays(1).atStartOfDay());
        }
        if (typeCode != null && !typeCode.isBlank()) {
            sql.append(" AND s.type_code = :t");
            p.put("t", typeCode);
        }
        if (projectId != null) {
            sql.append(" AND (s.project_id = :p OR EXISTS (SELECT 1 FROM equipment_shift_allocations a"
                     + " WHERE a.shift_id = s.id AND a.project_id = :p))");
            p.put("p", projectId);
        }
        sql.append(" ORDER BY s.date_from DESC");
        return jdbc.queryForList(sql.toString(), p);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable Long id) {
        var shift = jdbc.queryForMap("SELECT * FROM equipment_shifts WHERE id = :id", Map.of("id", id));
        var allocations = allocations(id);
        BigDecimal cost = cost(shift);
        BigDecimal allocated = allocations.stream()
                .map(a -> new BigDecimal(String.valueOf(a.getOrDefault("amount", "0"))))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Object> result = new HashMap<>();
        result.put("shift", shift);
        result.put("allocations", allocations);
        result.put("allocated_amount", allocated);
        result.put("unallocated_amount", cost != null ? cost.subtract(allocated) : null);
        return result;
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        var kh = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO equipment_shifts (type_code, supplier_id, supplier_name, date_from, date_to,
                    resource_qty, resource_unit, planned_cost, actual_cost, trip_id, project_id, note, created_by)
                VALUES (:type, :supId, :supName, :from, :to, :qty, :unit, :planned, :actual,
                        :trip, :project, :note, :author)
                """, params(body), kh, new String[]{"id"});
        Long id = ((Number) kh.getKeys().get("id")).longValue();
        audit.created("EQUIPMENT_SHIFT", id);
        return get(id);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        jdbc.update("""
                UPDATE equipment_shifts SET type_code = :type, supplier_id = :supId,
                       supplier_name = :supName, date_from = :from, date_to = :to,
                       resource_qty = :qty, resource_unit = :unit, planned_cost = :planned,
                       actual_cost = :actual, trip_id = :trip, project_id = :project, note = :note
                WHERE id = :id
                """, params(body).addValue("id", id));
        audit.action("EQUIPMENT_SHIFT", id, "UPDATE", null);
        return get(id);
    }

    @GetMapping("/{id}/allocations")
    public List<Map<String, Object>> allocations(@PathVariable Long id) {
        return jdbc.queryForList("""
                SELECT a.*, b.address AS building_address, r.number AS request_number,
                       p.code AS project_code
                FROM equipment_shift_allocations a
                LEFT JOIN buildings b ON b.id = a.building_id
                LEFT JOIN requests r ON r.id = a.request_id
                LEFT JOIN projects p ON p.id = a.project_id
                WHERE a.shift_id = :s ORDER BY a.id
                """, Map.of("s", id));
    }

    /**
     * Замена набора распределений целиком. Контроль итога — предупреждением,
     * а не запретом: бывает, что часть стоимости осознанно не разносится.
     */
    @PutMapping("/{id}/allocations")
    public Map<String, Object> setAllocations(@PathVariable Long id,
                                              @RequestBody List<Map<String, Object>> allocations) {
        var shift = jdbc.queryForMap("SELECT * FROM equipment_shifts WHERE id = :id", Map.of("id", id));
        BigDecimal cost = cost(shift);
        jdbc.update("DELETE FROM equipment_shift_allocations WHERE shift_id = :s", Map.of("s", id));

        for (var a : allocations) {
            String basis = String.valueOf(a.getOrDefault("basis", "AMOUNT"));
            BigDecimal basisValue = dec(a.get("basis_value"));
            BigDecimal amount = dec(a.get("amount"));
            // Процент и часы/рейсы пересчитываются в деньги здесь, чтобы в
            // себестоимости адреса всегда лежала сумма, а не формула.
            if (amount == null && cost != null && basisValue != null) {
                amount = switch (basis) {
                    case "PERCENT" -> cost.multiply(basisValue).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                    case "HOURS", "RUNS" -> {
                        BigDecimal resource = dec(shift.get("resource_qty"));
                        yield (resource != null && resource.compareTo(BigDecimal.ZERO) > 0)
                                ? cost.multiply(basisValue).divide(resource, 2, RoundingMode.HALF_UP)
                                : BigDecimal.ZERO;
                    }
                    default -> basisValue;
                };
            }
            if (amount == null) amount = BigDecimal.ZERO;

            Map<String, Object> p = new HashMap<>();
            p.put("s", id);
            p.put("b", num(a.get("building_id")));
            p.put("r", num(a.get("request_id")));
            p.put("p", num(a.get("project_id")));
            p.put("basis", basis);
            p.put("bv", basisValue);
            p.put("amount", amount);
            p.put("note", a.get("note"));
            jdbc.update("""
                    INSERT INTO equipment_shift_allocations (shift_id, building_id, request_id, project_id,
                        basis, basis_value, amount, note)
                    VALUES (:s, :b, :r, :p, :basis, :bv, :amount, :note)
                    """, p);
        }
        audit.action("EQUIPMENT_SHIFT", id, "ALLOCATE", allocations.size() + " распределений");
        return get(id);
    }

    /** Смены с нераспределённой стоимостью — попадают в контроль качества данных. */
    @GetMapping("/unallocated")
    public List<Map<String, Object>> unallocated() {
        return jdbc.queryForList("""
                SELECT s.id, s.type_code, s.date_from, COALESCE(s.actual_cost, s.planned_cost) AS cost,
                       COALESCE((SELECT SUM(a.amount) FROM equipment_shift_allocations a
                                 WHERE a.shift_id = s.id), 0) AS allocated
                FROM equipment_shifts s
                WHERE COALESCE(s.actual_cost, s.planned_cost) IS NOT NULL
                  AND COALESCE(s.actual_cost, s.planned_cost) >
                      COALESCE((SELECT SUM(a.amount) FROM equipment_shift_allocations a
                                WHERE a.shift_id = s.id), 0)
                ORDER BY s.date_from DESC
                """, Map.of());
    }

    private MapSqlParameterSource params(Map<String, Object> body) {
        String typeCode = (String) body.get("type_code");
        if (typeCode == null) throw new BusinessRuleException("Не указан тип спецтехники");
        return new MapSqlParameterSource()
                .addValue("type", typeCode)
                .addValue("supId", num(body.get("supplier_id")))
                .addValue("supName", body.get("supplier_name"))
                .addValue("from", dateTime(body.get("date_from")))
                .addValue("to", dateTime(body.get("date_to")))
                .addValue("qty", dec(body.get("resource_qty")))
                .addValue("unit", body.get("resource_unit"))
                .addValue("planned", dec(body.get("planned_cost")))
                .addValue("actual", dec(body.get("actual_cost")))
                .addValue("trip", num(body.get("trip_id")))
                .addValue("project", num(body.get("project_id")))
                .addValue("note", body.get("note"))
                .addValue("author", CurrentUser.name());
    }

    private static BigDecimal cost(Map<String, Object> shift) {
        BigDecimal actual = dec(shift.get("actual_cost"));
        return actual != null ? actual : dec(shift.get("planned_cost"));
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
        String s = String.valueOf(v);
        return s.length() <= 10 ? LocalDate.parse(s).atStartOfDay() : LocalDateTime.parse(s);
    }
}
