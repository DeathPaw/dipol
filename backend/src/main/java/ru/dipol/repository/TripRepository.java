package ru.dipol.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Выезды. Связь с заявками M:N: один выезд закрывает несколько адресов, одна
 * заявка может выполняться несколькими выездами (US-C1, US-C2).
 */
@Repository
public class TripRepository {

    private static final String SELECT = """
            SELECT t.*, v.plate AS vehicle_plate, e.full_name AS responsible_name,
                   (SELECT COUNT(*) FROM trip_requests tr WHERE tr.trip_id = t.id) AS requests_count,
                   (SELECT COUNT(*) FROM trip_members tm WHERE tm.trip_id = t.id) AS members_count
            FROM trips t
            LEFT JOIN vehicles v ON v.id = t.vehicle_id
            LEFT JOIN employees e ON e.id = t.responsible_employee_id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public TripRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> search(LocalDate from, LocalDate to, String status,
                                            Long employeeId, Long vehicleId) {
        StringBuilder sql = new StringBuilder(SELECT + " WHERE 1=1");
        Map<String, Object> p = new HashMap<>();
        if (from != null) {
            sql.append(" AND t.trip_date >= :from");
            p.put("from", from);
        }
        if (to != null) {
            sql.append(" AND t.trip_date <= :to");
            p.put("to", to);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND t.status = :s");
            p.put("s", status);
        }
        if (employeeId != null) {
            sql.append(" AND (t.responsible_employee_id = :e"
                     + " OR EXISTS (SELECT 1 FROM trip_members m WHERE m.trip_id = t.id AND m.employee_id = :e))");
            p.put("e", employeeId);
        }
        if (vehicleId != null) {
            sql.append(" AND t.vehicle_id = :v");
            p.put("v", vehicleId);
        }
        sql.append(" ORDER BY t.trip_date DESC, t.id DESC");
        return jdbc.queryForList(sql.toString(), p);
    }

    public Map<String, Object> findById(Long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(SELECT + " WHERE t.id = :id", Map.of("id", id));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Long create(LocalDate date, LocalTime from, LocalTime to, Long responsibleId,
                       Long vehicleId, Long crewTemplateId, String note, String actor) {
        var kh = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO trips (trip_date, slot_from, slot_to, responsible_employee_id, vehicle_id,
                                   crew_template_id, note, created_by)
                VALUES (:d, :f, :t, :r, :v, :c, :n, :a)
                """, new MapSqlParameterSource().addValue("d", date).addValue("f", from)
                     .addValue("t", to).addValue("r", responsibleId).addValue("v", vehicleId)
                     .addValue("c", crewTemplateId).addValue("n", note).addValue("a", actor),
                kh, new String[]{"id"});
        return ((Number) kh.getKeys().get("id")).longValue();
    }

    public void update(Long id, LocalDate date, LocalTime from, LocalTime to, Long responsibleId,
                       Long vehicleId, String status, BigDecimal mileage, String mileageSource,
                       String routeNote, String note) {
        var p = new MapSqlParameterSource()
                .addValue("id", id).addValue("d", date).addValue("f", from).addValue("t", to)
                .addValue("r", responsibleId).addValue("v", vehicleId).addValue("s", status)
                .addValue("m", mileage).addValue("ms", mileageSource)
                .addValue("rn", routeNote).addValue("n", note);
        jdbc.update("""
                UPDATE trips SET trip_date = :d, slot_from = :f, slot_to = :t,
                       responsible_employee_id = :r, vehicle_id = :v, status = :s,
                       mileage_total = :m, mileage_source = :ms, route_note = :rn, note = :n,
                       updated_at = NOW()
                WHERE id = :id
                """, p);
    }

    // ---- состав заявок ------------------------------------------------------

    public List<Map<String, Object>> requests(Long tripId) {
        return jdbc.queryForList("""
                SELECT r.id, r.number, r.status, r.apartment, r.description, r.urgency,
                       b.address AS building_address, p.code AS project_code, tr.sort_order,
                       (SELECT COALESCE(SUM(a.km),0) FROM trip_mileage_allocations a
                        WHERE a.trip_id = :t AND a.request_id = r.id) AS km
                FROM trip_requests tr
                JOIN requests r ON r.id = tr.request_id
                JOIN buildings b ON b.id = r.building_id
                JOIN projects p ON p.id = r.project_id
                WHERE tr.trip_id = :t ORDER BY tr.sort_order, r.id
                """, Map.of("t", tripId));
    }

    public void addRequest(Long tripId, Long requestId, int sortOrder) {
        jdbc.update("""
                INSERT INTO trip_requests (trip_id, request_id, sort_order) VALUES (:t, :r, :o)
                ON CONFLICT (trip_id, request_id) DO UPDATE SET sort_order = EXCLUDED.sort_order
                """, Map.of("t", tripId, "r", requestId, "o", sortOrder));
    }

    public void removeRequest(Long tripId, Long requestId) {
        jdbc.update("DELETE FROM trip_requests WHERE trip_id = :t AND request_id = :r",
                Map.of("t", tripId, "r", requestId));
    }

    /** Кандидаты в выезд: незакрытые заявки, совместимые с проектом (US-C2). */
    public List<Map<String, Object>> candidates(Long projectId, String query, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT r.id, r.number, r.status, r.apartment, r.urgency, r.priority, r.planned_date,
                       b.address AS building_address, p.code AS project_code
                FROM requests r
                JOIN buildings b ON b.id = r.building_id
                JOIN projects p ON p.id = r.project_id
                WHERE r.status NOT IN ('ACT_SIGNED','CANCELLED') AND NOT r.is_closed
                """);
        Map<String, Object> pr = new HashMap<>();
        if (projectId != null) {
            sql.append(" AND r.project_id = :p");
            pr.put("p", projectId);
        }
        if (query != null && !query.isBlank()) {
            sql.append(" AND (b.address ILIKE :q OR r.number ILIKE :q OR r.apartment ILIKE :q)");
            pr.put("q", "%" + query.trim() + "%");
        }
        sql.append(" ORDER BY r.priority DESC, r.urgency, r.received_at LIMIT :lim");
        pr.put("lim", limit);
        return jdbc.queryForList(sql.toString(), pr);
    }

    // ---- состав бригады -----------------------------------------------------

    public List<Map<String, Object>> members(Long tripId) {
        return jdbc.queryForList("""
                SELECT m.employee_id, e.full_name, e.short_name, m.is_planned, m.is_fact, m.role_code
                FROM trip_members m JOIN employees e ON e.id = m.employee_id
                WHERE m.trip_id = :t ORDER BY e.full_name
                """, Map.of("t", tripId));
    }

    public void setMembers(Long tripId, List<Map<String, Object>> members) {
        jdbc.update("DELETE FROM trip_members WHERE trip_id = :t", Map.of("t", tripId));
        for (var m : members) {
            Map<String, Object> p = new HashMap<>();
            p.put("t", tripId);
            p.put("e", ((Number) m.get("employee_id")).longValue());
            p.put("pl", !Boolean.FALSE.equals(m.get("is_planned")));
            p.put("f", Boolean.TRUE.equals(m.get("is_fact")));
            p.put("r", m.get("role_code"));
            jdbc.update("""
                    INSERT INTO trip_members (trip_id, employee_id, is_planned, is_fact, role_code)
                    VALUES (:t, :e, :pl, :f, :r)
                    """, p);
        }
    }

    /**
     * Конфликты занятости: один сотрудник в двух выездах на одну дату, один
     * автомобиль в двух выездах (US-C3, US-Q2). Подсвечиваются, но не блокируют:
     * бывает, что бригада реально успевает в два места.
     */
    public List<Map<String, Object>> conflicts(Long tripId) {
        return jdbc.queryForList("""
                SELECT 'EMPLOYEE' AS kind, e.full_name AS name, t2.id AS other_trip_id, t2.trip_date
                FROM trip_members m1
                JOIN trip_members m2 ON m2.employee_id = m1.employee_id AND m2.trip_id <> m1.trip_id
                JOIN trips t1 ON t1.id = m1.trip_id
                JOIN trips t2 ON t2.id = m2.trip_id AND t2.trip_date = t1.trip_date
                JOIN employees e ON e.id = m1.employee_id
                WHERE m1.trip_id = :t AND t2.status <> 'CANCELLED'
                UNION ALL
                SELECT 'VEHICLE', v.plate, t2.id, t2.trip_date
                FROM trips t1
                JOIN trips t2 ON t2.vehicle_id = t1.vehicle_id AND t2.id <> t1.id
                                 AND t2.trip_date = t1.trip_date AND t2.status <> 'CANCELLED'
                JOIN vehicles v ON v.id = t1.vehicle_id
                WHERE t1.id = :t
                """, Map.of("t", tripId));
    }

    // ---- маршрут и пробег ---------------------------------------------------

    public List<Map<String, Object>> points(Long tripId) {
        return jdbc.queryForList("""
                SELECT tp.*, b.address AS building_address, r.number AS request_number
                FROM trip_points tp
                LEFT JOIN buildings b ON b.id = tp.building_id
                LEFT JOIN requests r ON r.id = tp.request_id
                WHERE tp.trip_id = :t ORDER BY tp.sort_order
                """, Map.of("t", tripId));
    }

    public void setPoints(Long tripId, List<Map<String, Object>> points) {
        jdbc.update("DELETE FROM trip_points WHERE trip_id = :t", Map.of("t", tripId));
        int order = 10;
        for (var pt : points) {
            Map<String, Object> p = new HashMap<>();
            p.put("t", tripId);
            p.put("o", order);
            p.put("k", pt.getOrDefault("point_kind", "OTHER"));
            p.put("r", num(pt.get("request_id")));
            p.put("b", num(pt.get("building_id")));
            p.put("n", pt.get("name"));
            p.put("lat", pt.get("lat"));
            p.put("lon", pt.get("lon"));
            jdbc.update("""
                    INSERT INTO trip_points (trip_id, sort_order, point_kind, request_id, building_id, name, lat, lon)
                    VALUES (:t, :o, :k, :r, :b, :n, :lat, :lon)
                    """, p);
            order += 10;
        }
    }

    public List<Map<String, Object>> mileageAllocations(Long tripId) {
        return jdbc.queryForList("""
                SELECT a.*, r.number AS request_number, b.address AS building_address
                FROM trip_mileage_allocations a
                JOIN requests r ON r.id = a.request_id
                JOIN buildings b ON b.id = r.building_id
                WHERE a.trip_id = :t ORDER BY r.number
                """, Map.of("t", tripId));
    }

    public void setMileageAllocation(Long tripId, Long requestId, BigDecimal km, boolean manual) {
        jdbc.update("""
                INSERT INTO trip_mileage_allocations (trip_id, request_id, km, is_manual)
                VALUES (:t, :r, :k, :m)
                ON CONFLICT (trip_id, request_id) DO UPDATE SET km = EXCLUDED.km, is_manual = EXCLUDED.is_manual
                """, Map.of("t", tripId, "r", requestId, "k", km, "m", manual));
    }

    public BigDecimal allocatedMileage(Long tripId) {
        BigDecimal v = jdbc.queryForObject(
                "SELECT COALESCE(SUM(km),0) FROM trip_mileage_allocations WHERE trip_id = :t",
                Map.of("t", tripId), BigDecimal.class);
        return v != null ? v : BigDecimal.ZERO;
    }

    // ---- перепланирование ---------------------------------------------------

    public void addReschedule(Long tripId, Long requestId, LocalDate oldDate, LocalDate newDate,
                              String reasonCode, String comment, String actor) {
        Map<String, Object> p = new HashMap<>();
        p.put("t", tripId);
        p.put("r", requestId);
        p.put("od", oldDate);
        p.put("nd", newDate);
        p.put("rc", reasonCode);
        p.put("c", comment);
        p.put("a", actor);
        jdbc.update("""
                INSERT INTO trip_reschedules (trip_id, request_id, old_date, new_date, reason_code, comment, actor)
                VALUES (:t, :r, :od, :nd, :rc, :c, :a)
                """, p);
    }

    public List<Map<String, Object>> reschedules(Long tripId) {
        return jdbc.queryForList("SELECT * FROM trip_reschedules WHERE trip_id = :t ORDER BY created_at DESC",
                Map.of("t", tripId));
    }

    private static Long num(Object v) {
        return v == null ? null : ((Number) v).longValue();
    }
}
