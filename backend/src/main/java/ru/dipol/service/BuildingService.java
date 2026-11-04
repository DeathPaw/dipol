package ru.dipol.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.dipol.audit.CurrentUser;
import ru.dipol.exception.BusinessRuleException;
import ru.dipol.exception.EntityNotFoundException;
import ru.dipol.model.Building;
import ru.dipol.repository.BuildingRepository;

import java.util.List;
import java.util.Optional;

/**
 * Сопоставление входящего адреса с карточкой дома.
 *
 * <p>Порядок ровно такой, как в разделе 7.1 бизнес-ТЗ:
 * внешний ID/ФИАС → сохранённый алиас источника → нормализованный ключ →
 * при неоднозначности решение оператора, после чего вариант запоминается.
 */
@Service
public class BuildingService {

    /** Результат сопоставления: либо дом, либо список кандидатов на разбор. */
    public record MatchResult(String status, Building building, List<Building> candidates, String matchedBy) {
        public static MatchResult matched(Building b, String by) {
            return new MatchResult("MATCHED", b, List.of(), by);
        }
        public static MatchResult ambiguous(List<Building> candidates) {
            return new MatchResult("AMBIGUOUS", null, candidates, null);
        }
        public static MatchResult notFound() {
            return new MatchResult("NOT_FOUND", null, List.of(), null);
        }
    }

    private final BuildingRepository repo;
    private final AddressService addresses;
    private final AuditService audit;

    public BuildingService(BuildingRepository repo, AddressService addresses, AuditService audit) {
        this.repo = repo;
        this.addresses = addresses;
        this.audit = audit;
    }

    public MatchResult match(String rawAddress, Integer legacyId, String fias, String source) {
        Optional<Building> byLegacy = repo.findByLegacyId(legacyId);
        if (byLegacy.isPresent()) return MatchResult.matched(byLegacy.get(), "LEGACY_ID");

        Optional<Building> byFias = repo.findByFias(fias);
        if (byFias.isPresent()) return MatchResult.matched(byFias.get(), "FIAS");

        String key = addresses.key(rawAddress);
        if (key.isBlank()) return MatchResult.notFound();

        Optional<Building> byAlias = repo.findByAlias(key, source);
        if (byAlias.isPresent()) return MatchResult.matched(byAlias.get(), "ALIAS");

        // Алиас мог быть сохранён от другого источника — он всё равно полезен.
        Optional<Building> byAnyAlias = repo.findByAlias(key, null);
        if (byAnyAlias.isPresent()) return MatchResult.matched(byAnyAlias.get(), "ALIAS_OTHER_SOURCE");

        List<Building> byKey = repo.findByAddressKey(key);
        if (byKey.size() == 1) return MatchResult.matched(byKey.get(0), "ADDRESS_KEY");
        if (byKey.size() > 1) return MatchResult.ambiguous(byKey);

        return MatchResult.notFound();
    }

    /**
     * Ручное решение оператора: закрепить вариант написания за домом.
     * После этого тот же адрес сопоставляется автоматически.
     */
    @Transactional
    public void rememberAlias(Long buildingId, String rawAddress, String source) {
        repo.findById(buildingId).orElseThrow(() -> new EntityNotFoundException("Дом", buildingId));
        String key = addresses.key(rawAddress);
        if (key.isBlank()) throw new BusinessRuleException("Пустой адрес нельзя сохранить как алиас");
        repo.addAlias(buildingId, addresses.normalizeDisplay(rawAddress), key, source, CurrentUser.name());
        audit.action("BUILDING", buildingId, "ALIAS_ADD", rawAddress + " (" + source + ")");
    }

    @Transactional
    public Building create(Building b) {
        Building withKey = withKey(b);
        List<Building> existing = repo.findByAddressKey(withKey.addressKey());
        if (!existing.isEmpty()) {
            throw new ru.dipol.exception.ConflictException(
                    "Дом с таким адресом уже есть: id=" + existing.get(0).id() + " «" + existing.get(0).address() + "»");
        }
        Building created = repo.create(withKey);
        audit.created("BUILDING", created.id());
        return created;
    }

    @Transactional
    public Building update(Long id, Building b) {
        Building before = repo.findById(id).orElseThrow(() -> new EntityNotFoundException("Дом", id));
        Building after = repo.update(id, withKey(b));
        audit.changed("BUILDING", id, "address", before.address(), after.address());
        audit.changed("BUILDING", id, "zheu_id", before.zheuId(), after.zheuId());
        audit.changed("BUILDING", id, "apartments_count", before.apartmentsCount(), after.apartmentsCount());
        audit.changed("BUILDING", id, "entrances_count", before.entrancesCount(), after.entrancesCount());
        // Старое написание не теряем: оно остаётся алиасом и продолжает сопоставляться.
        if (!before.addressKey().equals(after.addressKey())) {
            repo.addAlias(id, before.address(), before.addressKey(), "RENAME", CurrentUser.name());
        }
        return after;
    }

    @Transactional
    public void merge(Long sourceId, Long targetId) {
        if (sourceId.equals(targetId)) throw new BusinessRuleException("Нельзя объединить дом сам с собой");
        Building source = repo.findById(sourceId).orElseThrow(() -> new EntityNotFoundException("Дом", sourceId));
        repo.findById(targetId).orElseThrow(() -> new EntityNotFoundException("Дом", targetId));
        repo.merge(sourceId, targetId);
        // Адрес поглощённого дома продолжает находиться — уже на целевой карточке.
        repo.addAlias(targetId, source.address(), source.addressKey(), "MERGE", CurrentUser.name());
        audit.action("BUILDING", sourceId, "MERGE", "объединён с домом id=" + targetId);
        audit.action("BUILDING", targetId, "MERGE_TARGET", "поглотил дом id=" + sourceId);
    }

    /**
     * Подъезд по квартире: сначала матрица подъездов, затем равномерное
     * деление как подсказка. Второй способ помечается неточным.
     */
    public record EntranceGuess(Integer entrance, String source, boolean exact) {}

    public EntranceGuess entranceFor(Long buildingId, Integer apartment) {
        Integer exact = repo.entranceByApartment(buildingId, apartment);
        if (exact != null) return new EntranceGuess(exact, "MATRIX", true);
        Building b = repo.findById(buildingId).orElseThrow(() -> new EntityNotFoundException("Дом", buildingId));
        Integer guess = addresses.guessEntrance(apartment, b.apartmentsPerEntrance(), b.entrancesCount());
        if (guess == null) return new EntranceGuess(null, "UNKNOWN", false);
        return new EntranceGuess(guess, "UNIFORM_SPLIT", false);
    }

    private Building withKey(Building b) {
        String display = addresses.normalizeDisplay(b.address());
        return new Building(b.id(), b.legacyId(), display, addresses.key(display), b.fiasId(), b.kladr(),
                b.zheuId(), b.zheuName(), b.floors(), b.entrancesCount(), b.apartmentsCount(),
                b.apartmentsPerEntrance(), b.roofType(), b.conditionNote(), b.roofAccess(),
                b.atticAccess(), b.basementAccess(), b.accessNote(), b.lat(), b.lon(),
                b.isActive(), b.mergedIntoId());
    }
}
