package ru.dipol.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;
import ru.dipol.dto.PageResponse;
import ru.dipol.model.Building;
import ru.dipol.model.BuildingAlias;
import ru.dipol.model.BuildingEntrance;
import ru.dipol.repository.BuildingRepository;
import ru.dipol.service.BuildingService;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/buildings")
public class BuildingController {

    private final BuildingRepository repo;
    private final BuildingService service;

    public BuildingController(BuildingRepository repo, BuildingService service) {
        this.repo = repo;
        this.service = service;
    }

    public record BuildingRequest(
            Integer legacyId,
            @NotBlank String address,
            String fiasId,
            String kladr,
            Long zheuId,
            Integer floors,
            Integer entrancesCount,
            Integer apartmentsCount,
            Integer apartmentsPerEntrance,
            String roofType,
            String conditionNote,
            String roofAccess,
            String atticAccess,
            String basementAccess,
            String accessNote,
            BigDecimal lat,
            BigDecimal lon,
            Boolean isActive
    ) {
        Building toModel() {
            return new Building(null, legacyId, address, null, fiasId, kladr, zheuId, null,
                    floors, entrancesCount, apartmentsCount, apartmentsPerEntrance, roofType,
                    conditionNote, roofAccess, atticAccess, basementAccess, accessNote,
                    lat, lon, isActive == null || isActive, null);
        }
    }

    @GetMapping
    public PageResponse<Building> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long zheuId,
            @RequestParam(defaultValue = "false") boolean includeInactive,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        List<Building> items = repo.search(q, zheuId, includeInactive, size, page * size);
        return PageResponse.of(items, repo.count(q, zheuId, includeInactive), page, size);
    }

    @GetMapping("/{id}")
    public Building get(@PathVariable Long id) {
        return repo.findById(id).orElseThrow(() -> new ru.dipol.exception.EntityNotFoundException("Дом", id));
    }

    @GetMapping("/{id}/summary")
    public Map<String, Object> summary(@PathVariable Long id) {
        return repo.summary(id);
    }

    @PostMapping
    public Building create(@Valid @RequestBody BuildingRequest req) {
        return service.create(req.toModel());
    }

    @PutMapping("/{id}")
    public Building update(@PathVariable Long id, @Valid @RequestBody BuildingRequest req) {
        return service.update(id, req.toModel());
    }

    /** Объединение дубля: заявки, алиасы и история переезжают на целевой дом. */
    @PostMapping("/{id}/merge")
    public Map<String, Object> merge(@PathVariable Long id, @RequestBody Map<String, Long> body) {
        service.merge(id, body.get("target_id"));
        return Map.of("status", "merged", "target_id", body.get("target_id"));
    }

    // ---- сопоставление адреса ----------------------------------------------

    /** Проверка входящего адреса до создания заявки или при импорте. */
    @GetMapping("/match")
    public BuildingService.MatchResult match(
            @RequestParam String address,
            @RequestParam(required = false) Integer legacyId,
            @RequestParam(required = false) String fias,
            @RequestParam(required = false) String source) {
        return service.match(address, legacyId, fias, source);
    }

    @GetMapping("/{id}/aliases")
    public List<BuildingAlias> aliases(@PathVariable Long id) {
        return repo.aliases(id);
    }

    @PostMapping("/{id}/aliases")
    public List<BuildingAlias> addAlias(@PathVariable Long id, @RequestBody Map<String, String> body) {
        service.rememberAlias(id, body.get("alias"), body.getOrDefault("source", "MANUAL"));
        return repo.aliases(id);
    }

    @DeleteMapping("/aliases/{aliasId}")
    public Map<String, String> deleteAlias(@PathVariable Long aliasId) {
        repo.deleteAlias(aliasId);
        return Map.of("status", "deleted");
    }

    // ---- подъезды и квартиры ------------------------------------------------

    @GetMapping("/{id}/entrances")
    public List<BuildingEntrance> entrances(@PathVariable Long id) {
        return repo.entrances(id);
    }

    @PutMapping("/{id}/entrances")
    public List<BuildingEntrance> saveEntrances(@PathVariable Long id,
                                                @RequestBody List<BuildingEntrance> entrances) {
        entrances.forEach(e -> repo.saveEntrance(id, e));
        return repo.entrances(id);
    }

    @DeleteMapping("/entrances/{entranceId}")
    public Map<String, String> deleteEntrance(@PathVariable Long entranceId) {
        repo.deleteEntrance(entranceId);
        return Map.of("status", "deleted");
    }

    /** Какой подъезд у квартиры и насколько ответ точен. */
    @GetMapping("/{id}/entrance-for")
    public BuildingService.EntranceGuess entranceFor(@PathVariable Long id, @RequestParam Integer apartment) {
        return service.entranceFor(id, apartment);
    }
}
