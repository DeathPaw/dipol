package ru.dipol.controller;

import org.springframework.web.bind.annotation.*;
import ru.dipol.audit.CurrentUser;
import ru.dipol.repository.ParameterRepository;
import ru.dipol.service.AuditService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Нормативы и диапазонные коэффициенты — версионируемые, с датой действия. */
@RestController
@RequestMapping("/api/parameters")
public class ParameterController {

    private final ParameterRepository repo;
    private final AuditService audit;

    public ParameterController(ParameterRepository repo, AuditService audit) {
        this.repo = repo;
        this.audit = audit;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return repo.findAll();
    }

    @GetMapping("/{id}/versions")
    public List<Map<String, Object>> versions(@PathVariable Long id) {
        return repo.versions(id);
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, String> body) {
        Long id = repo.createParameter(body.get("code"), body.get("name"), body.get("unit_code"),
                body.get("value_type"), body.get("description"));
        audit.created("PARAMETER", id);
        return Map.of("id", id);
    }

    /** Новое значение норматива. Прошлые расчёты остаются на своей версии. */
    @PostMapping("/{id}/versions")
    public List<Map<String, Object>> addVersion(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        BigDecimal num = body.get("value_num") != null
                ? new BigDecimal(String.valueOf(body.get("value_num"))) : null;
        LocalDate from = body.get("valid_from") != null
                ? LocalDate.parse(String.valueOf(body.get("valid_from"))) : LocalDate.now();
        repo.addVersion(id, num, (String) body.get("value_text"), from,
                CurrentUser.name(), (String) body.get("comment"));
        audit.action("PARAMETER", id, "VERSION", "новое значение с " + from + ": " + num);
        return repo.versions(id);
    }

    @GetMapping("/coefficients/{code}")
    public List<Map<String, Object>> coefficients(@PathVariable String code) {
        return repo.coefficientRanges(code);
    }

    @PostMapping("/coefficients/{code}")
    public List<Map<String, Object>> addCoefficient(@PathVariable String code,
                                                    @RequestBody Map<String, Object> body) {
        java.util.Map<String, Object> r = new java.util.HashMap<>(body);
        r.put("code", code);
        r.putIfAbsent("from_inclusive", true);
        r.putIfAbsent("to_inclusive", false);
        r.putIfAbsent("valid_from", LocalDate.now());
        r.putIfAbsent("valid_to", null);
        r.putIfAbsent("author", CurrentUser.name());
        r.putIfAbsent("comment", null);
        r.putIfAbsent("from_value", null);
        r.putIfAbsent("to_value", null);
        repo.saveCoefficientRange(r);
        audit.action("COEFFICIENT", null, "CREATE", code + ": k=" + body.get("k"));
        return repo.coefficientRanges(code);
    }

    /** Проверка: какой k получится для конкретной производительности. */
    @GetMapping("/coefficients/{code}/resolve")
    public Map<String, Object> resolve(@PathVariable String code,
                                       @RequestParam BigDecimal value,
                                       @RequestParam(required = false) LocalDate date) {
        BigDecimal k = repo.coefficient(code, value, date != null ? date : LocalDate.now());
        return k != null ? Map.of("value", value, "k", k)
                         : Map.of("value", value, "error", "Диапазон для этого значения не задан");
    }
}
