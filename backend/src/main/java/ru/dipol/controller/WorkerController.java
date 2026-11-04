package ru.dipol.controller;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.*;
import ru.dipol.exception.ForbiddenException;
import ru.dipol.repository.AppUserRepository;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Мобильный кабинет исполнителя (эпик D).
 *
 * <p>Экран показывает только назначенные на сотрудника работы: адрес, место,
 * работу, плановый объём, комментарий и доступ. Финансы чужих людей и чужие
 * работы не показываются (US-D1). Вход — по PIN, без логина и пароля.
 */
@RestController
@RequestMapping("/api/worker")
public class WorkerController {

    private final NamedParameterJdbcTemplate jdbc;
    private final AppUserRepository users;

    public WorkerController(NamedParameterJdbcTemplate jdbc, AppUserRepository users) {
        this.jdbc = jdbc;
        this.users = users;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> body) {
        var user = users.findByPin(body.get("pin"))
                .orElseThrow(() -> new ForbiddenException("Неверный PIN"));
        Map<String, Object> result = new HashMap<>();
        result.put("user_id", user.id());
        result.put("employee_id", user.employeeId());
        result.put("display_name", user.displayName());
        return result;
    }

    /** Задачи на день: адрес, место, работа, плановый объём, доступ. */
    @GetMapping("/tasks")
    public List<Map<String, Object>> tasks(@RequestParam Long employeeId,
                                           @RequestParam(required = false) LocalDate date) {
        LocalDate day = date != null ? date : LocalDate.now();
        return jdbc.queryForList("""
                SELECT i.id AS item_id, i.status AS item_status, i.planned_qty, i.fact_qty,
                       i.accepted_qty, i.unit_code, i.man_hours, i.note,
                       s.name AS sku_name, s.requires_photo,
                       r.id AS request_id, r.number, r.apartment, r.entrance, r.place_note,
                       r.description, r.urgency,
                       b.address, b.roof_access, b.attic_access, b.access_note,
                       t.id AS trip_id, t.trip_date
                FROM request_item_assignees a
                JOIN request_items i ON i.id = a.request_item_id
                JOIN requests r ON r.id = i.request_id
                JOIN buildings b ON b.id = r.building_id
                JOIN sku s ON s.id = i.sku_id
                LEFT JOIN trip_requests tr ON tr.request_id = r.id
                LEFT JOIN trips t ON t.id = tr.trip_id AND t.trip_date = :d
                WHERE a.employee_id = :e
                  AND i.status NOT IN ('ACCEPTED','CANCELLED')
                  AND r.status NOT IN ('ACT_SIGNED','CANCELLED')
                  AND (t.trip_date = :d OR r.planned_date = :d OR r.planned_date IS NULL)
                ORDER BY r.urgency, r.number
                """, Map.of("e", employeeId, "d", day));
    }

    /** Маршрут дня: точки выезда в порядке объезда. */
    @GetMapping("/route")
    public List<Map<String, Object>> route(@RequestParam Long employeeId,
                                           @RequestParam(required = false) LocalDate date) {
        LocalDate day = date != null ? date : LocalDate.now();
        return jdbc.queryForList("""
                SELECT tp.sort_order, tp.point_kind, tp.name, b.address, r.number AS request_number,
                       t.id AS trip_id, v.plate
                FROM trips t
                JOIN trip_members m ON m.trip_id = t.id AND m.employee_id = :e
                LEFT JOIN trip_points tp ON tp.trip_id = t.id
                LEFT JOIN buildings b ON b.id = tp.building_id
                LEFT JOIN requests r ON r.id = tp.request_id
                LEFT JOIN vehicles v ON v.id = t.vehicle_id
                WHERE t.trip_date = :d AND t.status <> 'CANCELLED'
                ORDER BY tp.sort_order
                """, Map.of("e", employeeId, "d", day));
    }

    /** Свой ФОТ за период — только свой, чужие суммы исполнителю не видны. */
    @GetMapping("/payroll")
    public Map<String, Object> payroll(@RequestParam Long employeeId,
                                       @RequestParam(required = false) LocalDate from,
                                       @RequestParam(required = false) LocalDate to) {
        LocalDate f = from != null ? from : LocalDate.now().withDayOfMonth(1);
        LocalDate t = to != null ? to : LocalDate.now();
        var entries = jdbc.queryForList("""
                SELECT pe.entry_date, pe.qty, pe.unit_code, pe.rate, pe.amount, pe.formula,
                       s.name AS sku_name, p.name AS project_name
                FROM payroll_entries pe
                LEFT JOIN sku s ON s.id = pe.sku_id
                JOIN projects p ON p.id = pe.project_id
                WHERE pe.employee_id = :e AND pe.status <> 'REVERSED'
                  AND pe.entry_date BETWEEN :f AND :t
                ORDER BY pe.entry_date DESC
                """, Map.of("e", employeeId, "f", f, "t", t));
        var total = jdbc.queryForMap("""
                SELECT COALESCE(SUM(amount),0) AS accrued FROM payroll_entries
                WHERE employee_id = :e AND status <> 'REVERSED' AND entry_date BETWEEN :f AND :t
                """, Map.of("e", employeeId, "f", f, "t", t));
        Map<String, Object> result = new HashMap<>(total);
        result.put("entries", entries);
        result.put("period_from", f);
        result.put("period_to", t);
        return result;
    }

    /**
     * Разрешённые правки карточки дома от исполнителя: доступы, комментарии
     * и наблюдения. Параметры дома и адрес рабочий не меняет (US-D1).
     */
    @PutMapping("/buildings/{id}/access")
    public Map<String, Object> updateAccess(@PathVariable Long id, @RequestBody Map<String, String> body) {
        // Непереданные поля остаются как были: COALESCE над SQL NULL, а не над
        // пустой строкой — иначе частичная правка затирала бы остальные доступы.
        Map<String, Object> p = new HashMap<>();
        p.put("id", id);
        p.put("ra", body.get("roof_access"));
        p.put("aa", body.get("attic_access"));
        p.put("ba", body.get("basement_access"));
        p.put("an", body.get("access_note"));
        jdbc.update("""
                UPDATE buildings SET roof_access = COALESCE(:ra, roof_access),
                       attic_access = COALESCE(:aa, attic_access),
                       basement_access = COALESCE(:ba, basement_access),
                       access_note = COALESCE(:an, access_note), updated_at = NOW()
                WHERE id = :id
                """, p);
        return Map.of("status", "updated");
    }
}
