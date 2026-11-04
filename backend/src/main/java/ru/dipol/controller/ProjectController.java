package ru.dipol.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;
import ru.dipol.audit.CurrentUser;
import ru.dipol.exception.BusinessRuleException;
import ru.dipol.exception.EntityNotFoundException;
import ru.dipol.model.Project;
import ru.dipol.repository.ProjectRepository;
import ru.dipol.service.AuditService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectRepository repo;
    private final AuditService audit;

    public ProjectController(ProjectRepository repo, AuditService audit) {
        this.repo = repo;
        this.audit = audit;
    }

    public record ProjectRequest(
            @NotBlank String code,
            @NotBlank String name,
            Long clientId,
            Long configId,
            String lifecycleType,
            LocalDate startedOn,
            String finishCriteria,
            LocalDate finishedOn,
            String status,
            Long responsibleEmployeeId,
            LocalDate seasonFrom,
            LocalDate seasonTo,
            String contractNumber,
            String note
    ) {
        Project toModel() {
            return new Project(null, code, name, clientId, null, configId, null, null, 1,
                    lifecycleType != null ? lifecycleType : "PERMANENT",
                    startedOn, finishCriteria, finishedOn,
                    status != null ? status : "ACTIVE",
                    responsibleEmployeeId, null, seasonFrom, seasonTo, contractNumber, note);
        }
    }

    @GetMapping
    public List<Project> list(@RequestParam(required = false) String status,
                              @RequestParam(required = false) Long clientId,
                              @RequestParam(required = false) Long configId) {
        return repo.findAll(status, clientId, configId);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable Long id) {
        Project p = repo.findById(id).orElseThrow(() -> new EntityNotFoundException("Проект", id));
        return Map.of(
                "project", p,
                "summary", repo.summary(id),
                "monthly_plans", repo.monthlyPlans(id),
                "buildings", repo.buildings(id)
        );
    }

    @PostMapping
    public Project create(@Valid @RequestBody ProjectRequest req) {
        validate(req);
        Project p = repo.create(req.toModel());
        audit.created("PROJECT", p.id());
        return p;
    }

    @PutMapping("/{id}")
    public Project update(@PathVariable Long id, @Valid @RequestBody ProjectRequest req) {
        validate(req);
        Project before = repo.findById(id).orElseThrow(() -> new EntityNotFoundException("Проект", id));
        Project after = repo.update(id, req.toModel());
        audit.changed("PROJECT", id, "name", before.name(), after.name());
        audit.changed("PROJECT", id, "responsible", before.responsibleEmployeeId(), after.responsibleEmployeeId());
        return after;
    }

    /**
     * Смена статуса проекта. Закрытый проект блокирует новые заявки, пока его
     * явно не возобновят с причиной (US-O5).
     */
    @PostMapping("/{id}/status")
    public Project changeStatus(@PathVariable Long id, @RequestBody Map<String, String> body) {
        Project p = repo.findById(id).orElseThrow(() -> new EntityNotFoundException("Проект", id));
        String to = body.get("status");
        String reason = body.get("reason");
        if (to == null) throw new BusinessRuleException("Не указан новый статус");
        if ("CLOSED".equals(to) && "ONE_OFF".equals(p.lifecycleType())
                && (p.finishCriteria() == null || p.finishCriteria().isBlank())) {
            throw new BusinessRuleException("У разового проекта должен быть критерий завершения");
        }
        if ("ACTIVE".equals(to) && "CLOSED".equals(p.status()) && (reason == null || reason.isBlank())) {
            throw new BusinessRuleException("Возобновление закрытого проекта требует причины");
        }
        repo.changeStatus(id, p.status(), to, reason, CurrentUser.name());
        audit.status("PROJECT", id, p.status(), to, reason);
        return repo.findById(id).orElseThrow();
    }

    /** Перевод проекта на новую версию правил конфигурации. */
    @PostMapping("/{id}/config-version")
    public Project applyConfigVersion(@PathVariable Long id, @RequestBody Map<String, Integer> body) {
        Project p = repo.findById(id).orElseThrow(() -> new EntityNotFoundException("Проект", id));
        Integer version = body.get("version");
        if (version == null) throw new BusinessRuleException("Не указана версия конфигурации");
        repo.applyConfigVersion(id, version);
        audit.changed("PROJECT", id, "config_version", p.configVersion(), version);
        return repo.findById(id).orElseThrow();
    }

    @PutMapping("/{id}/monthly-plan")
    public List<Map<String, Object>> saveMonthlyPlan(@PathVariable Long id,
                                                     @RequestBody Map<String, Object> body) {
        repo.saveMonthlyPlan(id,
                LocalDate.parse(String.valueOf(body.get("period_month"))),
                num(body.get("payroll_plan")), num(body.get("materials_plan")),
                num(body.get("volume_plan")), (String) body.get("volume_unit"),
                (String) body.get("note"));
        audit.action("PROJECT", id, "PLAN", "обновлён план на " + body.get("period_month"));
        return repo.monthlyPlans(id);
    }

    @PutMapping("/{id}/buildings")
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> setBuildings(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        List<Number> raw = (List<Number>) body.getOrDefault("building_ids", List.of());
        repo.setBuildings(id, raw.stream().map(Number::longValue).toList());
        return repo.buildings(id);
    }

    private void validate(ProjectRequest req) {
        if ("ONE_OFF".equals(req.lifecycleType())) {
            if (req.startedOn() == null) {
                throw new BusinessRuleException("У разового проекта обязательна дата начала");
            }
            if (req.finishCriteria() == null || req.finishCriteria().isBlank()) {
                throw new BusinessRuleException("У разового проекта обязателен критерий завершения");
            }
        }
    }

    private static BigDecimal num(Object v) {
        if (v == null) return null;
        return new BigDecimal(String.valueOf(v));
    }
}
