package ru.dipol.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;
import ru.dipol.exception.BusinessRuleException;
import ru.dipol.model.Sku;
import ru.dipol.repository.SkuRepository;
import ru.dipol.service.AuditService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sku")
public class SkuController {

    private final SkuRepository repo;
    private final AuditService audit;

    public SkuController(SkuRepository repo, AuditService audit) {
        this.repo = repo;
        this.audit = audit;
    }

    public record SkuRequest(
            @NotBlank String code,
            @NotBlank String name,
            Long groupId,
            @NotBlank String unitCode,
            String actWording,
            Boolean requiresPhoto,
            Boolean isActive,
            String description,
            Integer sortOrder
    ) {
        Sku toModel() {
            return new Sku(null, code, name, groupId, null, unitCode, actWording,
                    Boolean.TRUE.equals(requiresPhoto),
                    isActive == null || isActive,
                    description,
                    sortOrder != null ? sortOrder : 100);
        }
    }

    @GetMapping
    public List<Sku> list(@RequestParam(defaultValue = "false") boolean includeInactive,
                          @RequestParam(required = false) Long projectId,
                          @RequestParam(required = false) Long configId) {
        if (projectId != null) return repo.findByProject(projectId);
        if (configId != null) return repo.findByConfig(configId);
        return repo.findAll(includeInactive);
    }

    @GetMapping("/groups")
    public List<Map<String, Object>> groups() {
        return repo.groups();
    }

    @PostMapping("/groups")
    public List<Map<String, Object>> createGroup(@RequestBody Map<String, Object> body) {
        repo.createGroup(String.valueOf(body.get("name")), (Integer) body.get("sort_order"));
        return repo.groups();
    }

    @PostMapping
    public Sku create(@Valid @RequestBody SkuRequest req) {
        Sku created = repo.create(req.toModel());
        audit.created("SKU", created.id());
        return created;
    }

    @PutMapping("/{id}")
    public Sku update(@PathVariable Long id, @Valid @RequestBody SkuRequest req) {
        Sku before = repo.findById(id).orElseThrow();
        Sku after = repo.update(id, req.toModel());
        audit.changed("SKU", id, "name", before.name(), after.name());
        audit.changed("SKU", id, "unit_code", before.unitCode(), after.unitCode());
        audit.changed("SKU", id, "is_active", before.isActive(), after.isActive());
        return after;
    }

    /**
     * Физического удаления нет: использованная в истории работа только
     * деактивируется, иначе ломаются закрытые заявки и начисления (US-N2).
     */
    @DeleteMapping("/{id}")
    public Sku deactivate(@PathVariable Long id) {
        Sku s = repo.findById(id).orElseThrow();
        if (!repo.isUsed(id)) {
            // Даже неиспользованную работу не удаляем: поведение должно быть
            // одинаковым, чтобы пользователь не гадал, что случится.
            audit.action("SKU", id, "DEACTIVATE", "работа не использовалась в истории");
        } else {
            audit.action("SKU", id, "DEACTIVATE", "работа использована в истории — только деактивация");
        }
        if (!s.isActive()) throw new BusinessRuleException("Работа уже неактивна");
        return repo.update(id, new Sku(null, s.code(), s.name(), s.groupId(), null, s.unitCode(),
                s.actWording(), s.requiresPhoto(), false, s.description(), s.sortOrder()));
    }
}
