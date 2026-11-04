package ru.dipol.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;
import ru.dipol.audit.CurrentUser;
import ru.dipol.exception.BusinessRuleException;
import ru.dipol.exception.EntityNotFoundException;
import ru.dipol.model.ProjectConfig;
import ru.dipol.repository.ProjectConfigRepository;
import ru.dipol.service.AuditService;

import java.util.List;
import java.util.Map;

/** Проектные конфигурации: правила направления живут в данных, а не в коде. */
@RestController
@RequestMapping("/api/project-configs")
public class ProjectConfigController {

    private final ProjectConfigRepository repo;
    private final AuditService audit;

    public ProjectConfigController(ProjectConfigRepository repo, AuditService audit) {
        this.repo = repo;
        this.audit = audit;
    }

    public record ConfigRequest(
            @NotBlank String code,
            @NotBlank String name,
            String description,
            Boolean useWorkObjects,
            String defaultLifecycle,
            List<String> allowedPaySchemes,
            String materialsMode,
            Boolean isActive
    ) {
        ProjectConfig toModel() {
            return new ProjectConfig(null, code, name, description,
                    Boolean.TRUE.equals(useWorkObjects),
                    defaultLifecycle != null ? defaultLifecycle : "PERMANENT",
                    allowedPaySchemes != null ? allowedPaySchemes : List.of("SHIFT", "PIECE_KPI"),
                    materialsMode != null ? materialsMode : "FACT",
                    1, isActive == null || isActive, 0);
        }
    }

    @GetMapping
    public List<ProjectConfig> list(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return repo.findAll(includeInactive);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable Long id) {
        ProjectConfig c = repo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Конфигурация", id));
        return Map.of(
                "config", c,
                "skus", repo.skus(id),
                "transitions", repo.transitions(id),
                "fields", repo.fields(id),
                "checklist", repo.checklist(id),
                "versions", repo.versions(id),
                "issues", repo.validationIssues(id)
        );
    }

    @PostMapping
    public ProjectConfig create(@Valid @RequestBody ConfigRequest req) {
        ProjectConfig c = repo.create(req.toModel(), CurrentUser.name());
        audit.created("PROJECT_CONFIG", c.id());
        return c;
    }

    @PutMapping("/{id}")
    public ProjectConfig update(@PathVariable Long id, @Valid @RequestBody ConfigRequest req) {
        ProjectConfig before = repo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Конфигурация", id));
        ProjectConfig after = repo.update(id, req.toModel());
        audit.changed("PROJECT_CONFIG", id, "name", before.name(), after.name());
        audit.changed("PROJECT_CONFIG", id, "is_active", before.isActive(), after.isActive());
        return after;
    }

    /** Выпуск новой версии правил: закрытые данные остаются на прежней. */
    @PostMapping("/{id}/versions")
    public Map<String, Object> bumpVersion(@PathVariable Long id, @RequestBody Map<String, String> body) {
        int version = repo.bumpVersion(id, body.get("rules_json"), CurrentUser.name(), body.get("comment"));
        audit.action("PROJECT_CONFIG", id, "VERSION", "выпущена версия " + version);
        return Map.of("version", version);
    }

    @PostMapping("/{id}/clone")
    public Map<String, Object> clone(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String code = body.get("code");
        String name = body.get("name");
        if (code == null || code.isBlank()) throw new BusinessRuleException("Нужен код новой конфигурации");
        if (repo.findByCode(code).isPresent()) {
            throw new ru.dipol.exception.ConflictException("Конфигурация с кодом «" + code + "» уже есть");
        }
        Long newId = repo.clone(id, code, name != null ? name : code, CurrentUser.name());
        audit.action("PROJECT_CONFIG", newId, "CLONE", "клонирована из id=" + id);
        // Клон создаётся неактивным: сперва правила проверяют, потом включают.
        return Map.of("id", newId, "is_active", false, "issues", repo.validationIssues(newId));
    }

    @PutMapping("/{id}/skus")
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> setSkus(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        List<Number> raw = (List<Number>) body.getOrDefault("sku_ids", List.of());
        repo.setSkus(id, raw.stream().map(Number::longValue).toList());
        audit.action("PROJECT_CONFIG", id, "SKUS", "обновлён состав работ");
        return repo.skus(id);
    }

    @PutMapping("/{id}/transitions")
    public List<Map<String, Object>> setTransitions(@PathVariable Long id,
                                                    @RequestBody List<Map<String, String>> transitions) {
        repo.setTransitions(id, transitions);
        audit.action("PROJECT_CONFIG", id, "TRANSITIONS", "обновлена статусная схема");
        return repo.transitions(id);
    }

    @PutMapping("/{id}/fields")
    public List<Map<String, Object>> setFields(@PathVariable Long id,
                                               @RequestBody List<Map<String, Object>> fields) {
        for (var f : fields) {
            repo.setField(id, String.valueOf(f.get("field_code")),
                    !Boolean.FALSE.equals(f.get("is_visible")),
                    Boolean.TRUE.equals(f.get("is_required")));
        }
        audit.action("PROJECT_CONFIG", id, "FIELDS", "обновлены поля заявки");
        return repo.fields(id);
    }

    @PutMapping("/{id}/checklist")
    public List<Map<String, Object>> setChecklist(@PathVariable Long id,
                                                  @RequestBody List<Map<String, Object>> items) {
        repo.setChecklist(id, items);
        audit.action("PROJECT_CONFIG", id, "CHECKLIST", "обновлён чек-лист закрытия");
        return repo.checklist(id);
    }

    /** Активация конфигурации: до включения система показывает, чего не хватает. */
    @PostMapping("/{id}/activate")
    public Map<String, Object> activate(@PathVariable Long id) {
        List<String> issues = repo.validationIssues(id);
        if (!issues.isEmpty()) {
            throw new BusinessRuleException("Конфигурация не готова: " + String.join("; ", issues));
        }
        ProjectConfig c = repo.findById(id).orElseThrow(() -> new EntityNotFoundException("Конфигурация", id));
        repo.update(id, new ProjectConfig(null, c.code(), c.name(), c.description(), c.useWorkObjects(),
                c.defaultLifecycle(), c.allowedPaySchemes(), c.materialsMode(), c.currentVersion(), true, 0));
        audit.action("PROJECT_CONFIG", id, "ACTIVATE", null);
        return Map.of("status", "active");
    }
}
