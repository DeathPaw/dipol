package ru.dipol.controller;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.*;
import ru.dipol.service.PermissionService;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Аналитика: операционный дашборд, план-факт, производственная себестоимость,
 * производительность и контроль качества данных.
 *
 * <p>Все показатели считаются из первичных записей, а не из введённых вручную
 * итогов (нефункциональное требование раздела 29).
 */
@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

    private final NamedParameterJdbcTemplate jdbc;
    private final PermissionService permissions;

    public AnalyticsController(NamedParameterJdbcTemplate jdbc, PermissionService permissions) {
        this.jdbc = jdbc;
        this.permissions = permissions;
    }

    /** Операционный дашборд: входящие, запланированные, в работе, незакрытые. */
    @GetMapping("/dashboard")
    public Map<String, Object> dashboard(@RequestParam(required = false) List<Long> projectIds,
                                         @RequestParam(required = false) Long zheuId) {
        Map<String, Object> p = new HashMap<>();
        StringBuilder filter = new StringBuilder();
        if (projectIds != null && !projectIds.isEmpty()) {
            filter.append(" AND r.project_id IN (:projects)");
            p.put("projects", projectIds);
        }
        if (zheuId != null) {
            filter.append(" AND r.zheu_id = :zheu");
            p.put("zheu", zheuId);
        }

        var byStatus = jdbc.queryForList("""
                SELECT r.status, s.name AS status_name, COUNT(*) AS count
                FROM requests r JOIN request_statuses s ON s.code = r.status
                WHERE 1=1
                """ + filter + " GROUP BY r.status, s.name, s.sort_order ORDER BY s.sort_order", p);

        var counters = jdbc.queryForMap("""
                SELECT
                  COUNT(*) FILTER (WHERE r.status = 'CREATED') AS new_requests,
                  COUNT(*) FILTER (WHERE r.status NOT IN ('ACT_SIGNED','CANCELLED')
                                     AND r.planned_date IS NULL) AS unplanned,
                  COUNT(*) FILTER (WHERE r.status NOT IN ('DONE','CUSTOMER_ACCEPTED','ACT_CREATED',
                                                          'ACT_SIGNED','CANCELLED')
                                     AND r.planned_date < CURRENT_DATE) AS overdue,
                  COUNT(*) FILTER (WHERE r.status = 'IN_PROGRESS') AS in_progress,
                  COUNT(*) FILTER (WHERE r.status = 'DONE') AS awaiting_acceptance,
                  COUNT(*) FILTER (WHERE r.status = 'CUSTOMER_ACCEPTED') AS without_act,
                  COUNT(*) FILTER (WHERE r.urgency = 'EMERGENCY'
                                     AND r.status NOT IN ('ACT_SIGNED','CANCELLED')) AS emergency_open
                FROM requests r WHERE 1=1
                """ + filter, p);

        var upcomingTrips = jdbc.queryForList("""
                SELECT t.id, t.trip_date, t.status, v.plate,
                       (SELECT COUNT(*) FROM trip_requests tr WHERE tr.trip_id = t.id) AS requests_count
                FROM trips t LEFT JOIN vehicles v ON v.id = t.vehicle_id
                WHERE t.trip_date >= CURRENT_DATE AND t.status <> 'CANCELLED'
                ORDER BY t.trip_date LIMIT 10
                """, Map.of());

        var openProblems = jdbc.queryForList("""
                SELECT pr.id, pr.description, pr.status, c.name AS category_name, r.number AS request_number
                FROM problems pr
                JOIN problem_categories c ON c.code = pr.category_code
                LEFT JOIN requests r ON r.id = pr.request_id
                WHERE pr.status IN ('OPEN','IN_PROGRESS')
                ORDER BY pr.created_at DESC LIMIT 10
                """, Map.of());

        Map<String, Object> result = new HashMap<>(counters);
        result.put("by_status", byStatus);
        result.put("upcoming_trips", upcomingTrips);
        result.put("open_problems", openProblems);
        return result;
    }

    /** План-факт ФОТ и материалов по проекту и месяцу (US-M2). */
    @GetMapping("/plan-fact")
    public List<Map<String, Object>> planFact(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        permissions.require("PAYROLL_VIEW", "Показатели ФОТ требуют права на ФОТ");
        return jdbc.queryForList("""
                SELECT p.id AS project_id, p.code, p.name, m.period_month,
                       m.payroll_plan, m.materials_plan,
                       COALESCE(pf.total, 0) AS payroll_fact,
                       COALESCE(mf.total, 0) AS materials_fact,
                       COALESCE(pf.total, 0) - COALESCE(m.payroll_plan, 0) AS payroll_diff,
                       COALESCE(mf.total, 0) - COALESCE(m.materials_plan, 0) AS materials_diff
                FROM projects p
                LEFT JOIN project_monthly_plans m ON m.project_id = p.id
                     AND m.period_month BETWEEN :f AND :t
                LEFT JOIN (SELECT project_id, period_month, SUM(amount) total FROM payroll_entries
                           WHERE status <> 'REVERSED' GROUP BY project_id, period_month) pf
                     ON pf.project_id = p.id AND pf.period_month = m.period_month
                LEFT JOIN (SELECT project_id, date_trunc('month', moved_at)::date AS period_month,
                                  SUM(amount) total FROM stock_movements
                           WHERE movement_type = 'ISSUE'
                           GROUP BY project_id, date_trunc('month', moved_at)::date) mf
                     ON mf.project_id = p.id AND mf.period_month = m.period_month
                WHERE p.status <> 'CLOSED'
                ORDER BY p.code, m.period_month
                """, Map.of("f", from, "t", to));
    }

    /**
     * Производственная себестоимость заявки: материалы, ФОТ, спецтехника,
     * транспорт и амортизация. Налоги, офис и прибыль в стартовый контур
     * не входят (US-M5).
     */
    @GetMapping("/cost/request/{id}")
    public Map<String, Object> requestCost(@PathVariable Long id) {
        permissions.require("COST_VIEW", "Себестоимость доступна с правом «Производственная себестоимость»");
        var row = jdbc.queryForMap("""
                SELECT
                  COALESCE((SELECT SUM(sm.amount) FROM stock_movements sm
                            WHERE sm.request_id = :r AND sm.movement_type = 'ISSUE'), 0) AS materials,
                  COALESCE((SELECT SUM(pe.amount) FROM payroll_entries pe
                            WHERE pe.request_id = :r AND pe.status <> 'REVERSED'), 0) AS payroll,
                  COALESCE((SELECT SUM(a.amount) FROM equipment_shift_allocations a
                            WHERE a.request_id = :r), 0) AS special_equipment,
                  COALESCE((SELECT SUM(ma.km) FROM trip_mileage_allocations ma
                            WHERE ma.request_id = :r), 0) AS km
                """, Map.of("r", id));
        java.math.BigDecimal materials = dec(row.get("materials"));
        java.math.BigDecimal payroll = dec(row.get("payroll"));
        java.math.BigDecimal equipment = dec(row.get("special_equipment"));
        java.math.BigDecimal total = materials.add(payroll).add(equipment);

        Map<String, Object> result = new HashMap<>(row);
        result.put("total", total);
        // Километраж показываем отдельной строкой: стоимость километра — тема
        // отдельного норматива, подставлять случайное число нельзя.
        result.put("note", "Транспорт учтён в километрах; денежный норматив за километр "
                + "не задан — добавьте параметр стоимости километра, чтобы включить его в итог");
        return result;
    }

    /** Себестоимость в разрезе проекта или адреса за период. */
    @GetMapping("/cost/by-project")
    public List<Map<String, Object>> costByProject(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        permissions.require("COST_VIEW", null);
        return jdbc.queryForList("""
                SELECT p.id, p.code, p.name,
                  COALESCE((SELECT SUM(sm.amount) FROM stock_movements sm
                            WHERE sm.project_id = p.id AND sm.movement_type = 'ISSUE'
                              AND sm.moved_at::date BETWEEN :f AND :t), 0) AS materials,
                  COALESCE((SELECT SUM(pe.amount) FROM payroll_entries pe
                            WHERE pe.project_id = p.id AND pe.status <> 'REVERSED'
                              AND pe.entry_date BETWEEN :f AND :t), 0) AS payroll,
                  COALESCE((SELECT SUM(a.amount) FROM equipment_shift_allocations a
                            JOIN equipment_shifts s ON s.id = a.shift_id
                            WHERE a.project_id = p.id AND s.date_from::date BETWEEN :f AND :t), 0)
                    AS special_equipment
                FROM projects p ORDER BY p.code
                """, Map.of("f", from, "t", to));
    }

    /** Производительность по работам и сотрудникам — совместимые единицы (US-M6). */
    @GetMapping("/productivity")
    public List<Map<String, Object>> productivity(@RequestParam LocalDate from, @RequestParam LocalDate to,
                                                  @RequestParam(required = false) Long skuId) {
        StringBuilder sql = new StringBuilder("""
                SELECT s.id AS sku_id, s.name AS sku_name, s.unit_code,
                       e.id AS employee_id, e.full_name,
                       SUM(COALESCE(i.accepted_qty, i.fact_qty)) AS qty,
                       SUM(i.man_hours) AS man_hours,
                       CASE WHEN SUM(i.man_hours) > 0
                            THEN ROUND(SUM(COALESCE(i.accepted_qty, i.fact_qty)) / SUM(i.man_hours), 3)
                       END AS per_hour
                FROM request_items i
                JOIN sku s ON s.id = i.sku_id
                JOIN requests r ON r.id = i.request_id
                JOIN request_item_assignees a ON a.request_item_id = i.id
                JOIN employees e ON e.id = a.employee_id
                WHERE i.status = 'ACCEPTED' AND r.done_at::date BETWEEN :f AND :t
                """);
        Map<String, Object> p = new HashMap<>();
        p.put("f", from);
        p.put("t", to);
        if (skuId != null) {
            sql.append(" AND s.id = :s");
            p.put("s", skuId);
        }
        sql.append(" GROUP BY s.id, s.name, s.unit_code, e.id, e.full_name ORDER BY s.name, e.full_name");
        return jdbc.queryForList(sql.toString(), p);
    }

    /**
     * Контроль качества данных (US-M4). Очередь собирается запросами, а не
     * ручным поиском ошибок при закрытии месяца.
     */
    @GetMapping("/data-quality")
    public List<Map<String, Object>> dataQuality() {
        List<Map<String, Object>> issues = new ArrayList<>();

        addIssues(issues, "REQUEST_NO_ITEMS", "WARNING",
                "Выполненные заявки без внесённого объёма работ", """
                SELECT r.id, r.number, r.building_id FROM requests r
                WHERE r.status IN ('DONE','CUSTOMER_ACCEPTED','ACT_CREATED','ACT_SIGNED')
                  AND NOT EXISTS (SELECT 1 FROM request_items i WHERE i.request_id = r.id)
                LIMIT 200
                """);

        addIssues(issues, "REQUEST_NO_PHOTO", "WARNING",
                "Закрытые заявки без фото", """
                SELECT r.id, r.number FROM requests r
                WHERE r.status IN ('ACT_CREATED','ACT_SIGNED')
                  AND NOT EXISTS (SELECT 1 FROM photos p
                                  WHERE p.entity_type = 'REQUEST' AND p.entity_id = r.id)
                LIMIT 200
                """);

        addIssues(issues, "EQUIPMENT_UNALLOCATED", "BLOCKING",
                "Смены спецтехники с нераспределённой стоимостью", """
                SELECT s.id, s.type_code, COALESCE(s.actual_cost, s.planned_cost) AS cost
                FROM equipment_shifts s
                WHERE COALESCE(s.actual_cost, s.planned_cost) >
                      COALESCE((SELECT SUM(a.amount) FROM equipment_shift_allocations a
                                WHERE a.shift_id = s.id), 0)
                LIMIT 200
                """);

        addIssues(issues, "MATERIAL_UNASSIGNED", "WARNING",
                "Списания материалов без проекта и заявки", """
                SELECT sm.id, sm.material_id, sm.qty FROM stock_movements sm
                WHERE sm.movement_type = 'ISSUE' AND sm.project_id IS NULL AND sm.request_id IS NULL
                LIMIT 200
                """);

        addIssues(issues, "PAYROLL_NO_TERMS", "BLOCKING",
                "Принятые работы без условия оплаты у исполнителя", """
                SELECT DISTINCT i.id AS request_item_id, a.employee_id, r.project_id
                FROM request_items i
                JOIN requests r ON r.id = i.request_id
                JOIN request_item_assignees a ON a.request_item_id = i.id
                WHERE i.status = 'ACCEPTED'
                  AND NOT EXISTS (SELECT 1 FROM employee_pay_terms t
                                  WHERE t.employee_id = a.employee_id AND t.project_id = r.project_id)
                LIMIT 200
                """);

        addIssues(issues, "BUILDING_NO_ENTRANCES", "WARNING",
                "Дома без матрицы подъездов — подъезд заявки считается приблизительно", """
                SELECT b.id, b.address FROM buildings b
                WHERE b.is_active AND b.apartments_per_entrance IS NULL
                  AND NOT EXISTS (SELECT 1 FROM building_entrances e WHERE e.building_id = b.id)
                  AND EXISTS (SELECT 1 FROM requests r WHERE r.building_id = b.id)
                LIMIT 200
                """);

        addIssues(issues, "EXPENSE_NO_BASIS", "WARNING",
                "Расходы кассы без основания и комментария", """
                SELECT t.id, t.occurred_on, t.amount FROM cash_transactions t
                WHERE t.direction = 'EXPENSE' AND t.status <> 'REVERSED'
                  AND NOT EXISTS (SELECT 1 FROM transaction_allocations a WHERE a.transaction_id = t.id)
                  AND (t.comment IS NULL OR t.comment = '')
                LIMIT 200
                """);

        return issues;
    }

    private void addIssues(List<Map<String, Object>> out, String type, String severity,
                           String title, String sql) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, Map.of());
        if (rows.isEmpty()) return;
        Map<String, Object> group = new HashMap<>();
        group.put("issue_type", type);
        group.put("severity", severity);
        group.put("title", title);
        group.put("count", rows.size());
        group.put("items", rows);
        out.add(group);
    }

    /** Перед закрытием месяца: что осталось незакрытым (US-K6). */
    @GetMapping("/period-check")
    public Map<String, Object> periodCheck(@RequestParam LocalDate month,
                                           @RequestParam(required = false) Long projectId) {
        LocalDate from = month.withDayOfMonth(1);
        LocalDate to = from.plusMonths(1).minusDays(1);
        Map<String, Object> p = new HashMap<>();
        p.put("f", from);
        p.put("t", to);
        String projectFilter = projectId != null ? " AND r.project_id = :p" : "";
        if (projectId != null) p.put("p", projectId);

        Map<String, Object> result = new HashMap<>();
        result.put("period_from", from);
        result.put("period_to", to);
        result.put("open_requests", jdbc.queryForList("""
                SELECT r.id, r.number, r.status FROM requests r
                WHERE r.received_at::date BETWEEN :f AND :t
                  AND r.status NOT IN ('ACT_SIGNED','CANCELLED')
                """ + projectFilter + " ORDER BY r.number LIMIT 500", p));
        result.put("missing_acts", jdbc.queryForList("""
                SELECT r.id, r.number FROM requests r
                WHERE r.done_at::date BETWEEN :f AND :t
                  AND NOT EXISTS (SELECT 1 FROM documents d
                                  WHERE d.request_id = r.id AND d.doc_type = 'ACT')
                """ + projectFilter + " LIMIT 500", p));
        result.put("draft_payroll", jdbc.queryForList("""
                SELECT pe.id, pe.employee_id, pe.amount FROM payroll_entries pe
                WHERE pe.entry_date BETWEEN :f AND :t AND pe.status = 'DRAFT'
                LIMIT 500
                """, Map.of("f", from, "t", to)));
        result.put("unallocated_purchases", jdbc.queryForList("""
                SELECT l.id, l.order_id, l.qty FROM purchase_order_lines l
                JOIN purchase_orders o ON o.id = l.order_id
                WHERE o.ordered_at::date BETWEEN :f AND :t
                  AND NOT EXISTS (SELECT 1 FROM material_allocations a WHERE a.order_line_id = l.id)
                LIMIT 500
                """, Map.of("f", from, "t", to)));
        return result;
    }

    private static java.math.BigDecimal dec(Object v) {
        return v == null ? java.math.BigDecimal.ZERO : new java.math.BigDecimal(String.valueOf(v));
    }
}
