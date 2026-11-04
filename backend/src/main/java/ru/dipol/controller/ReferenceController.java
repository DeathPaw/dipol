package ru.dipol.controller;

import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;
import ru.dipol.exception.BusinessRuleException;
import ru.dipol.repository.ReferenceRepository;
import ru.dipol.service.AuditService;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** CRUD простых справочников «код → название». */
@RestController
@RequestMapping("/api/references")
public class ReferenceController {

    private final ReferenceRepository repo;
    private final AuditService audit;

    public ReferenceController(ReferenceRepository repo, AuditService audit) {
        this.repo = repo;
        this.audit = audit;
    }

    public record ReferenceRequest(@NotBlank String code, @NotBlank String name, Integer sortOrder) {}

    @GetMapping
    public Set<String> list() {
        return repo.keys();
    }

    @GetMapping("/{key}")
    public List<Map<String, Object>> items(@PathVariable String key) {
        return repo.findAll(key);
    }

    @PostMapping("/{key}")
    public List<Map<String, Object>> create(@PathVariable String key, @RequestBody ReferenceRequest req) {
        assertEditable(key);
        repo.create(key, req.code(), req.name(), req.sortOrder());
        audit.action("REFERENCE:" + key, null, "CREATE", req.code() + " — " + req.name());
        return repo.findAll(key);
    }

    @PutMapping("/{key}/{code}")
    public List<Map<String, Object>> update(@PathVariable String key, @PathVariable String code,
                                            @RequestBody ReferenceRequest req) {
        assertEditable(key);
        repo.update(key, code, req.name(), req.sortOrder());
        audit.action("REFERENCE:" + key, null, "UPDATE", code + " → " + req.name());
        return repo.findAll(key);
    }

    @DeleteMapping("/{key}/{code}")
    public List<Map<String, Object>> delete(@PathVariable String key, @PathVariable String code) {
        assertEditable(key);
        repo.delete(key, code);
        audit.action("REFERENCE:" + key, null, "DELETE", code);
        return repo.findAll(key);
    }

    private void assertEditable(String key) {
        if (repo.isReadOnly(key)) {
            throw new BusinessRuleException(
                    "Справочник «" + key + "» системный: на его кодах держится логика расчётов и статусов");
        }
    }
}
