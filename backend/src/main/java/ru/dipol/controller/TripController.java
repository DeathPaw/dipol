package ru.dipol.controller;

import org.springframework.web.bind.annotation.*;
import ru.dipol.audit.CurrentUser;
import ru.dipol.exception.BusinessRuleException;
import ru.dipol.exception.EntityNotFoundException;
import ru.dipol.repository.RequestRepository;
import ru.dipol.repository.TripRepository;
import ru.dipol.service.AuditService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/trips")
public class TripController {

    private final TripRepository repo;
    private final RequestRepository requests;
    private final AuditService audit;

    public TripController(TripRepository repo, RequestRepository requests, AuditService audit) {
        this.repo = repo;
        this.requests = requests;
        this.audit = audit;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) LocalDate from,
                                          @RequestParam(required = false) LocalDate to,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) Long employeeId,
                                          @RequestParam(required = false) Long vehicleId) {
        return repo.search(from, to, status, employeeId, vehicleId);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable Long id) {
        Map<String, Object> trip = repo.findById(id);
        if (trip == null) throw new EntityNotFoundException("Выезд", id);
        return Map.of(
                "trip", trip,
                "requests", repo.requests(id),
                "members", repo.members(id),
                "points", repo.points(id),
                "mileage_allocations", repo.mileageAllocations(id),
                "allocated_mileage", repo.allocatedMileage(id),
                "conflicts", repo.conflicts(id),
                "reschedules", repo.reschedules(id)
        );
    }

    @GetMapping("/candidates")
    public List<Map<String, Object>> candidates(@RequestParam(required = false) Long projectId,
                                                @RequestParam(required = false) String q,
                                                @RequestParam(defaultValue = "100") int limit) {
        return repo.candidates(projectId, q, limit);
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        LocalDate date = LocalDate.parse(String.valueOf(body.get("trip_date")));
        Long id = repo.create(date, time(body.get("slot_from")), time(body.get("slot_to")),
                num(body.get("responsible_employee_id")), num(body.get("vehicle_id")),
                num(body.get("crew_template_id")), (String) body.get("note"), CurrentUser.name());
        audit.created("TRIP", id);
        return repo.findById(id);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Map<String, Object> before = repo.findById(id);
        if (before == null) throw new EntityNotFoundException("Выезд", id);
        LocalDate newDate = LocalDate.parse(String.valueOf(body.get("trip_date")));
        LocalDate oldDate = ((java.sql.Date) before.get("trip_date")).toLocalDate();

        // Перенос даты всегда фиксируется с причиной: срывы по погоде, доступу
        // и ресурсам должны становиться анализируемыми (US-C6).
        if (!newDate.equals(oldDate)) {
            String reason = (String) body.get("reschedule_reason");
            if (reason == null || reason.isBlank()) {
                throw new BusinessRuleException("Перенос даты выезда требует причины");
            }
            repo.addReschedule(id, null, oldDate, newDate, reason,
                    (String) body.get("reschedule_comment"), CurrentUser.name());
            audit.changed("TRIP", id, "trip_date", oldDate, newDate);
        }

        repo.update(id, newDate, time(body.get("slot_from")), time(body.get("slot_to")),
                num(body.get("responsible_employee_id")), num(body.get("vehicle_id")),
                (String) body.getOrDefault("status", before.get("status")),
                dec(body.get("mileage_total")), (String) body.get("mileage_source"),
                (String) body.get("route_note"), (String) body.get("note"));
        return repo.findById(id);
    }

    @PostMapping("/{id}/requests")
    public List<Map<String, Object>> addRequest(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long requestId = num(body.get("request_id"));
        repo.addRequest(id, requestId, body.get("sort_order") != null
                ? ((Number) body.get("sort_order")).intValue() : 100);
        requests.addEvent(requestId, "PLANNED", "Включена в выезд №" + id, null, null, CurrentUser.name());
        audit.action("TRIP", id, "REQUEST_ADD", "заявка id=" + requestId);
        return repo.requests(id);
    }

    @DeleteMapping("/{id}/requests/{requestId}")
    public List<Map<String, Object>> removeRequest(@PathVariable Long id, @PathVariable Long requestId) {
        repo.removeRequest(id, requestId);
        requests.addEvent(requestId, "PLANNED", "Исключена из выезда №" + id, null, null, CurrentUser.name());
        return repo.requests(id);
    }

    @PutMapping("/{id}/members")
    public List<Map<String, Object>> setMembers(@PathVariable Long id,
                                                @RequestBody List<Map<String, Object>> members) {
        repo.setMembers(id, members);
        audit.action("TRIP", id, "MEMBERS", "обновлён состав: " + members.size() + " чел.");
        return repo.members(id);
    }

    @PutMapping("/{id}/points")
    public List<Map<String, Object>> setPoints(@PathVariable Long id,
                                               @RequestBody List<Map<String, Object>> points) {
        repo.setPoints(id, points);
        return repo.points(id);
    }

    /**
     * Распределение пробега по заявкам. Сумма контролируется относительно
     * общего пробега выезда: расхождение возвращается предупреждением (US-Q5).
     */
    @PutMapping("/{id}/mileage")
    public Map<String, Object> setMileage(@PathVariable Long id,
                                          @RequestBody List<Map<String, Object>> allocations) {
        Map<String, Object> trip = repo.findById(id);
        if (trip == null) throw new EntityNotFoundException("Выезд", id);
        for (var a : allocations) {
            repo.setMileageAllocation(id, num(a.get("request_id")), dec(a.get("km")),
                    Boolean.TRUE.equals(a.get("is_manual")));
        }
        BigDecimal total = dec(trip.get("mileage_total"));
        BigDecimal allocated = repo.allocatedMileage(id);
        String warning = null;
        if (total != null && total.compareTo(BigDecimal.ZERO) > 0
                && allocated.compareTo(total) != 0) {
            warning = "Распределено " + allocated + " км из " + total + " км";
        }
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("allocations", repo.mileageAllocations(id));
        result.put("allocated", allocated);
        result.put("total", total);
        result.put("warning", warning);
        return result;
    }

    /** Равномерное распределение как стартовая точка — потом правится вручную. */
    @PostMapping("/{id}/mileage/distribute")
    public Map<String, Object> distribute(@PathVariable Long id) {
        Map<String, Object> trip = repo.findById(id);
        if (trip == null) throw new EntityNotFoundException("Выезд", id);
        BigDecimal total = dec(trip.get("mileage_total"));
        List<Map<String, Object>> reqs = repo.requests(id);
        if (total == null || reqs.isEmpty()) {
            throw new BusinessRuleException("Нужен общий пробег выезда и хотя бы одна заявка");
        }
        BigDecimal per = total.divide(BigDecimal.valueOf(reqs.size()), 2, java.math.RoundingMode.HALF_UP);
        for (var r : reqs) {
            repo.setMileageAllocation(id, ((Number) r.get("id")).longValue(), per, false);
        }
        return Map.of("allocations", repo.mileageAllocations(id), "per_request", per);
    }

    private static Long num(Object v) {
        return v == null ? null : ((Number) v).longValue();
    }

    private static BigDecimal dec(Object v) {
        return v == null ? null : new BigDecimal(String.valueOf(v));
    }

    private static LocalTime time(Object v) {
        if (v == null || String.valueOf(v).isBlank()) return null;
        return LocalTime.parse(String.valueOf(v));
    }
}
