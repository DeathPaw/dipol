package ru.dipol.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;
import ru.dipol.audit.CurrentUser;
import ru.dipol.exception.EntityNotFoundException;
import ru.dipol.model.Zheu;
import ru.dipol.repository.ZheuRepository;
import ru.dipol.service.AuditService;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/zheu")
public class ZheuController {

    private final ZheuRepository repo;
    private final AuditService audit;

    public ZheuController(ZheuRepository repo, AuditService audit) {
        this.repo = repo;
        this.audit = audit;
    }

    public record ZheuRequest(
            Integer legacyId,
            @NotBlank String name,
            String headName,
            String address,
            String phone,
            String email,
            String settlements,
            Boolean isActive
    ) {
        Zheu toModel() {
            return new Zheu(null, legacyId, name, headName, address, phone, email, settlements,
                    isActive == null || isActive);
        }
    }

    @GetMapping
    public List<Zheu> list(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return repo.findAll(includeInactive);
    }

    @GetMapping("/{id}")
    public Zheu get(@PathVariable Long id) {
        return repo.findById(id).orElseThrow(() -> new EntityNotFoundException("ЖЭУ", id));
    }

    @PostMapping
    public Zheu create(@Valid @RequestBody ZheuRequest req) {
        Zheu z = repo.create(req.toModel());
        audit.created("ZHEU", z.id());
        return z;
    }

    @PutMapping("/{id}")
    public Zheu update(@PathVariable Long id, @Valid @RequestBody ZheuRequest req) {
        Zheu before = repo.findById(id).orElseThrow(() -> new EntityNotFoundException("ЖЭУ", id));
        Zheu after = repo.update(id, req.toModel());
        audit.changed("ZHEU", id, "name", before.name(), after.name());
        audit.changed("ZHEU", id, "head_name", before.headName(), after.headName());
        return after;
    }

    /** Смена ЖЭУ у дома — с сохранением истории связи. */
    @PostMapping("/{id}/buildings/{buildingId}")
    public Map<String, Object> link(@PathVariable Long id, @PathVariable Long buildingId) {
        repo.linkBuilding(buildingId, id, CurrentUser.name());
        audit.action("BUILDING", buildingId, "ZHEU_LINK", "ЖЭУ id=" + id);
        return Map.of("status", "linked", "zheu_id", id, "building_id", buildingId);
    }

    /** ЖЭУ дома на дату — так акт задним числом получает корректные реквизиты. */
    @GetMapping("/for-building")
    public Zheu forBuilding(@RequestParam Long buildingId,
                            @RequestParam(required = false) LocalDate date) {
        LocalDate on = date != null ? date : LocalDate.now();
        return repo.forBuildingOn(buildingId, on)
                .orElseThrow(() -> new EntityNotFoundException(
                        "ЖЭУ для дома id=" + buildingId + " на " + on + " не найдено"));
    }
}
