package ru.dipol.repository;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import ru.dipol.model.Zheu;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class ZheuRepository {

    private static final RowMapper<Zheu> MAPPER = (rs, n) -> new Zheu(
            rs.getLong("id"),
            rs.getObject("legacy_id", Integer.class),
            rs.getString("name"),
            rs.getString("head_name"),
            rs.getString("address"),
            rs.getString("phone"),
            rs.getString("email"),
            rs.getString("settlements"),
            rs.getBoolean("is_active")
    );

    private final NamedParameterJdbcTemplate jdbc;

    public ZheuRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Zheu> findAll(boolean includeInactive) {
        return jdbc.query("SELECT * FROM zheu" + (includeInactive ? "" : " WHERE is_active = TRUE")
                + " ORDER BY name", MAPPER);
    }

    public Optional<Zheu> findById(Long id) {
        return jdbc.query("SELECT * FROM zheu WHERE id = :id", Map.of("id", id), MAPPER)
                .stream().findFirst();
    }

    public Optional<Zheu> findByLegacyId(Integer legacyId) {
        if (legacyId == null) return Optional.empty();
        return jdbc.query("SELECT * FROM zheu WHERE legacy_id = :l", Map.of("l", legacyId), MAPPER)
                .stream().findFirst();
    }

    public Zheu create(Zheu z) {
        var kh = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO zheu (legacy_id, name, head_name, address, phone, email, settlements, is_active)
                VALUES (:legacy, :name, :head, :addr, :phone, :email, :settl, :active)
                """, params(z), kh, new String[]{"id"});
        return findById(((Number) kh.getKeys().get("id")).longValue()).orElseThrow();
    }

    public Zheu update(Long id, Zheu z) {
        jdbc.update("""
                UPDATE zheu SET name = :name, head_name = :head, address = :addr, phone = :phone,
                       email = :email, settlements = :settl, is_active = :active, updated_at = NOW()
                WHERE id = :id
                """, params(z).addValue("id", id));
        return findById(id).orElseThrow();
    }

    /**
     * Связь дома с ЖЭУ меняется с сохранением истории: печатные формы должны
     * брать ЖЭУ, действовавшее на дату документа (US-A2).
     */
    public void linkBuilding(Long buildingId, Long zheuId, String actor) {
        Map<String, Object> p = Map.of("b", buildingId, "z", zheuId, "a", actor);
        jdbc.update("UPDATE building_zheu_history SET valid_to = CURRENT_DATE"
                + " WHERE building_id = :b AND valid_to IS NULL", Map.of("b", buildingId));
        jdbc.update("""
                INSERT INTO building_zheu_history (building_id, zheu_id, valid_from, actor)
                VALUES (:b, :z, CURRENT_DATE, :a)
                """, p);
        jdbc.update("UPDATE buildings SET zheu_id = :z, updated_at = NOW() WHERE id = :b",
                Map.of("b", buildingId, "z", zheuId));
    }

    /** ЖЭУ дома на конкретную дату — для акта задним числом. */
    public Optional<Zheu> forBuildingOn(Long buildingId, java.time.LocalDate date) {
        return jdbc.query("""
                SELECT z.* FROM building_zheu_history h JOIN zheu z ON z.id = h.zheu_id
                WHERE h.building_id = :b AND h.valid_from <= :d
                  AND (h.valid_to IS NULL OR h.valid_to > :d)
                ORDER BY h.valid_from DESC LIMIT 1
                """, Map.of("b", buildingId, "d", date), MAPPER).stream().findFirst();
    }

    private MapSqlParameterSource params(Zheu z) {
        return new MapSqlParameterSource()
                .addValue("legacy", z.legacyId())
                .addValue("name", z.name())
                .addValue("head", z.headName())
                .addValue("addr", z.address())
                .addValue("phone", z.phone())
                .addValue("email", z.email())
                .addValue("settl", z.settlements())
                .addValue("active", z.isActive());
    }
}
