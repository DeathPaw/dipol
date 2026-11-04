package ru.dipol.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;
import ru.dipol.exception.EntityNotFoundException;
import ru.dipol.model.Employee;
import ru.dipol.repository.EmployeeRepository;
import ru.dipol.service.AuditService;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/employees")
public class EmployeeController {

    private final EmployeeRepository repo;
    private final AuditService audit;

    public EmployeeController(EmployeeRepository repo, AuditService audit) {
        this.repo = repo;
        this.audit = audit;
    }

    public record EmployeeRequest(
            @NotBlank String fullName,
            String shortName, String roleCode, String position,
            String phone, String email,
            LocalDate hiredOn, LocalDate firedOn,
            Boolean isActive, String note,
            List<Long> skuIds
    ) {
        Employee toModel() {
            return new Employee(null, fullName, shortName, roleCode, null, position, phone, email,
                    hiredOn, firedOn, isActive == null || isActive, note, skuIds);
        }
    }

    @GetMapping
    public List<Employee> list(@RequestParam(defaultValue = "false") boolean includeInactive,
                               @RequestParam(required = false) String roleCode) {
        return repo.findAll(includeInactive, roleCode);
    }

    @GetMapping("/{id}")
    public Employee get(@PathVariable Long id) {
        return repo.findById(id).orElseThrow(() -> new EntityNotFoundException("Сотрудник", id));
    }

    @PostMapping
    public Employee create(@Valid @RequestBody EmployeeRequest req) {
        Employee e = repo.create(req.toModel());
        audit.created("EMPLOYEE", e.id());
        return e;
    }

    @PutMapping("/{id}")
    public Employee update(@PathVariable Long id, @Valid @RequestBody EmployeeRequest req) {
        Employee before = repo.findById(id).orElseThrow(() -> new EntityNotFoundException("Сотрудник", id));
        Employee after = repo.update(id, req.toModel());
        audit.changed("EMPLOYEE", id, "full_name", before.fullName(), after.fullName());
        audit.changed("EMPLOYEE", id, "role_code", before.roleCode(), after.roleCode());
        audit.changed("EMPLOYEE", id, "is_active", before.isActive(), after.isActive());
        return after;
    }

    /** Шаблон состава — подсказка при планировании, факт всегда по людям (US-C3). */
    @GetMapping("/crew-templates")
    public List<Map<String, Object>> crewTemplates() {
        return repo.crewTemplates();
    }

    @PostMapping("/crew-templates")
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> createCrewTemplate(@RequestBody Map<String, Object> body) {
        List<Number> raw = (List<Number>) body.getOrDefault("employee_ids", List.of());
        repo.createCrewTemplate(String.valueOf(body.get("name")),
                raw.stream().map(Number::longValue).toList());
        return repo.crewTemplates();
    }
}
