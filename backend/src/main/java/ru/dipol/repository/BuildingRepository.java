package ru.dipol.repository;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import ru.dipol.model.Building;
import ru.dipol.model.BuildingAlias;
import ru.dipol.model.BuildingEntrance;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class BuildingRepository {

    private static final String SELECT = """
            SELECT b.*, z.name AS zheu_name
            FROM buildings b LEFT JOIN zheu z ON z.id = b.zheu_id
            """;

    private static final RowMapper<Building> MAPPER = (rs, n) -> new Building(
            rs.getLong("id"),
            rs.getObject("legacy_id", Integer.class),
            rs.getString("address"),
            rs.getString("address_key"),
            rs.getString("fias_id"),
            rs.getString("kladr"),
            rs.getObject("zheu_id", Long.class),
            rs.getString("zheu_name"),
            rs.getObject("floors", Integer.class),
            rs.getObject("entrances_count", Integer.class),
            rs.getObject("apartments_count", Integer.class),
            rs.getObject("apartments_per_entrance", Integer.class),
            rs.getString("roof_type"),
            rs.getString("condition_note"),
            rs.getString("roof_access"),
            rs.getString("attic_access"),
            rs.getString("basement_access"),
            rs.getString("access_note"),
            rs.getBigDecimal("lat"),
            rs.getBigDecimal("lon"),
            rs.getBoolean("is_active"),
            rs.getObject("merged_into_id", Long.class)
    );

    private static final RowMapper<BuildingAlias> ALIAS_MAPPER = (rs, n) -> new BuildingAlias(
            rs.getLong("id"), rs.getLong("building_id"), rs.getString("alias"),
            rs.getString("alias_key"), rs.getString("source"), rs.getString("created_by"),
            rs.getTimestamp("created_at").toLocalDateTime());

    private static final RowMapper<BuildingEntrance> ENTRANCE_MAPPER = (rs, n) -> new BuildingEntrance(
            rs.getLong("id"), rs.getLong("building_id"), rs.getObject("number", Integer.class),
            rs.getObject("apartment_from", Integer.class), rs.getObject("apartment_to", Integer.class),
            rs.getObject("floors", Integer.class), rs.getString("note"));

    private final NamedParameterJdbcTemplate jdbc;

    public BuildingRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Building> search(String query, Long zheuId, boolean includeInactive, int limit, int offset) {
        StringBuilder sql = new StringBuilder(SELECT + " WHERE 1=1");
        Map<String, Object> p = new HashMap<>();
        if (!includeInactive) sql.append(" AND b.is_active = TRUE");
        if (query != null && !query.isBlank()) {
            sql.append(" AND (b.address ILIKE :q OR CAST(b.legacy_id AS TEXT) = :exact"
                     + " OR EXISTS (SELECT 1 FROM building_aliases a WHERE a.building_id = b.id AND a.alias ILIKE :q))");
            p.put("q", "%" + query.trim() + "%");
            p.put("exact", query.trim());
        }
        if (zheuId != null) {
            sql.append(" AND b.zheu_id = :z");
            p.put("z", zheuId);
        }
        sql.append(" ORDER BY b.address LIMIT :lim OFFSET :off");
        p.put("lim", limit);
        p.put("off", offset);
        return jdbc.query(sql.toString(), p, MAPPER);
    }

    public long count(String query, Long zheuId, boolean includeInactive) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM buildings b WHERE 1=1");
        Map<String, Object> p = new HashMap<>();
        if (!includeInactive) sql.append(" AND b.is_active = TRUE");
        if (query != null && !query.isBlank()) {
            sql.append(" AND (b.address ILIKE :q OR CAST(b.legacy_id AS TEXT) = :exact)");
            p.put("q", "%" + query.trim() + "%");
            p.put("exact", query.trim());
        }
        if (zheuId != null) {
            sql.append(" AND b.zheu_id = :z");
            p.put("z", zheuId);
        }
        Long c = jdbc.queryForObject(sql.toString(), p, Long.class);
        return c != null ? c : 0;
    }

    public Optional<Building> findById(Long id) {
        return jdbc.query(SELECT + " WHERE b.id = :id", Map.of("id", id), MAPPER).stream().findFirst();
    }

    public Optional<Building> findByLegacyId(Integer legacyId) {
        if (legacyId == null) return Optional.empty();
        return jdbc.query(SELECT + " WHERE b.legacy_id = :l", Map.of("l", legacyId), MAPPER)
                .stream().findFirst();
    }

    public Optional<Building> findByFias(String fias) {
        if (fias == null || fias.isBlank()) return Optional.empty();
        return jdbc.query(SELECT + " WHERE b.fias_id = :f", Map.of("f", fias), MAPPER)
                .stream().findFirst();
    }

    /** Кандидаты по нормализованному ключу. Больше одного — неоднозначность на ручной разбор. */
    public List<Building> findByAddressKey(String key) {
        return jdbc.query(SELECT + " WHERE b.address_key = :k AND b.is_active = TRUE",
                Map.of("k", key), MAPPER);
    }

    public Optional<Building> findByAlias(String aliasKey, String source) {
        Map<String, Object> p = new HashMap<>();
        p.put("k", aliasKey);
        p.put("s", source);
        String sourceClause = source == null ? "" : " AND a.source = :s";
        return jdbc.query(SELECT + " JOIN building_aliases a ON a.building_id = b.id"
                        + " WHERE a.alias_key = :k" + sourceClause + " LIMIT 1", p, MAPPER)
                .stream().findFirst();
    }

    public Building create(Building b) {
        var kh = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO buildings (legacy_id, address, address_key, fias_id, kladr, zheu_id,
                    floors, entrances_count, apartments_count, apartments_per_entrance, roof_type,
                    condition_note, roof_access, attic_access, basement_access, access_note,
                    lat, lon, is_active)
                VALUES (:legacy, :addr, :key, :fias, :kladr, :zheu, :floors, :entr, :apts, :ape,
                        :roof, :cond, :ra, :aa, :ba, :an, :lat, :lon, :active)
                """, params(b), kh, new String[]{"id"});
        return findById(((Number) kh.getKeys().get("id")).longValue()).orElseThrow();
    }

    public Building update(Long id, Building b) {
        jdbc.update("""
                UPDATE buildings SET address = :addr, address_key = :key, fias_id = :fias, kladr = :kladr,
                       zheu_id = :zheu, floors = :floors, entrances_count = :entr, apartments_count = :apts,
                       apartments_per_entrance = :ape, roof_type = :roof, condition_note = :cond,
                       roof_access = :ra, attic_access = :aa, basement_access = :ba, access_note = :an,
                       lat = :lat, lon = :lon, is_active = :active, updated_at = NOW()
                WHERE id = :id
                """, params(b).addValue("id", id));
        return findById(id).orElseThrow();
    }

    /** Дом не удаляют: его объединяют с дублем, история остаётся (US-A1). */
    public void merge(Long sourceId, Long targetId) {
        Map<String, Object> p = Map.of("s", sourceId, "t", targetId);
        jdbc.update("UPDATE requests SET building_id = :t WHERE building_id = :s", p);
        jdbc.update("UPDATE building_aliases SET building_id = :t WHERE building_id = :s", p);
        jdbc.update("UPDATE rk3_objects SET building_id = :t WHERE building_id = :s", p);
        jdbc.update("UPDATE stock_movements SET building_id = :t WHERE building_id = :s", p);
        jdbc.update("UPDATE buildings SET is_active = FALSE, merged_into_id = :t, updated_at = NOW() WHERE id = :s", p);
    }

    private MapSqlParameterSource params(Building b) {
        return new MapSqlParameterSource()
                .addValue("legacy", b.legacyId())
                .addValue("addr", b.address())
                .addValue("key", b.addressKey())
                .addValue("fias", b.fiasId())
                .addValue("kladr", b.kladr())
                .addValue("zheu", b.zheuId())
                .addValue("floors", b.floors())
                .addValue("entr", b.entrancesCount())
                .addValue("apts", b.apartmentsCount())
                .addValue("ape", b.apartmentsPerEntrance())
                .addValue("roof", b.roofType())
                .addValue("cond", b.conditionNote())
                .addValue("ra", b.roofAccess())
                .addValue("aa", b.atticAccess())
                .addValue("ba", b.basementAccess())
                .addValue("an", b.accessNote())
                .addValue("lat", b.lat())
                .addValue("lon", b.lon())
                .addValue("active", b.isActive());
    }

    // ---- алиасы -------------------------------------------------------------

    public List<BuildingAlias> aliases(Long buildingId) {
        return jdbc.query("SELECT * FROM building_aliases WHERE building_id = :b ORDER BY created_at",
                Map.of("b", buildingId), ALIAS_MAPPER);
    }

    public void addAlias(Long buildingId, String alias, String aliasKey, String source, String actor) {
        Map<String, Object> p = new HashMap<>();
        p.put("b", buildingId);
        p.put("a", alias);
        p.put("k", aliasKey);
        p.put("s", source != null ? source : "MANUAL");
        p.put("u", actor);
        jdbc.update("""
                INSERT INTO building_aliases (building_id, alias, alias_key, source, created_by)
                VALUES (:b, :a, :k, :s, :u)
                ON CONFLICT (alias_key, source) DO NOTHING
                """, p);
    }

    public void deleteAlias(Long aliasId) {
        jdbc.update("DELETE FROM building_aliases WHERE id = :id", Map.of("id", aliasId));
    }

    // ---- подъезды -----------------------------------------------------------

    public List<BuildingEntrance> entrances(Long buildingId) {
        return jdbc.query("SELECT * FROM building_entrances WHERE building_id = :b ORDER BY number",
                Map.of("b", buildingId), ENTRANCE_MAPPER);
    }

    public void saveEntrance(Long buildingId, BuildingEntrance e) {
        Map<String, Object> p = new HashMap<>();
        p.put("b", buildingId);
        p.put("n", e.number());
        p.put("f", e.apartmentFrom());
        p.put("t", e.apartmentTo());
        p.put("fl", e.floors());
        p.put("note", e.note());
        jdbc.update("""
                INSERT INTO building_entrances (building_id, number, apartment_from, apartment_to, floors, note)
                VALUES (:b, :n, :f, :t, :fl, :note)
                ON CONFLICT (building_id, number) DO UPDATE SET
                    apartment_from = EXCLUDED.apartment_from,
                    apartment_to   = EXCLUDED.apartment_to,
                    floors         = EXCLUDED.floors,
                    note           = EXCLUDED.note
                """, p);
    }

    public void deleteEntrance(Long id) {
        jdbc.update("DELETE FROM building_entrances WHERE id = :id", Map.of("id", id));
    }

    /** Подъезд по матрице квартир. Возвращает null, если матрица не заполнена. */
    public Integer entranceByApartment(Long buildingId, Integer apartment) {
        if (apartment == null) return null;
        return jdbc.query("""
                SELECT number FROM building_entrances
                WHERE building_id = :b AND apartment_from IS NOT NULL AND apartment_to IS NOT NULL
                  AND :a BETWEEN apartment_from AND apartment_to
                ORDER BY number LIMIT 1
                """, Map.of("b", buildingId, "a", apartment),
                (rs, n) -> rs.getInt("number")).stream().findFirst().orElse(null);
    }

    /** Сводка по дому для карточки: заявки, ремонты, последняя работа. */
    public Map<String, Object> summary(Long buildingId) {
        return jdbc.queryForMap("""
                SELECT
                  (SELECT COUNT(*) FROM requests r WHERE r.building_id = :b) AS requests_total,
                  (SELECT COUNT(*) FROM requests r WHERE r.building_id = :b
                       AND r.status NOT IN ('ACT_SIGNED','CANCELLED')) AS requests_active,
                  (SELECT COUNT(*) FROM requests r WHERE r.building_id = :b AND r.is_warranty) AS warranty_count,
                  (SELECT MAX(r.done_at) FROM requests r WHERE r.building_id = :b) AS last_done_at,
                  (SELECT COUNT(*) FROM rk3_objects o WHERE o.building_id = :b) AS rk3_count
                """, Map.of("b", buildingId));
    }
}
