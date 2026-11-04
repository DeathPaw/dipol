package ru.dipol.repository;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import ru.dipol.model.Sku;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class SkuRepository {

    private static final String SELECT = """
            SELECT s.*, g.name AS group_name
            FROM sku s LEFT JOIN sku_groups g ON g.id = s.group_id
            """;

    private static final RowMapper<Sku> MAPPER = (rs, n) -> new Sku(
            rs.getLong("id"),
            rs.getString("code"),
            rs.getString("name"),
            rs.getObject("group_id", Long.class),
            rs.getString("group_name"),
            rs.getString("unit_code"),
            rs.getString("act_wording"),
            rs.getBoolean("requires_photo"),
            rs.getBoolean("is_active"),
            rs.getString("description"),
            rs.getInt("sort_order")
    );

    private final NamedParameterJdbcTemplate jdbc;

    public SkuRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Sku> findAll(boolean includeInactive) {
        String where = includeInactive ? "" : " WHERE s.is_active = TRUE";
        return jdbc.query(SELECT + where + " ORDER BY s.sort_order, s.name", MAPPER);
    }

    public Optional<Sku> findById(Long id) {
        return jdbc.query(SELECT + " WHERE s.id = :id", Map.of("id", id), MAPPER).stream().findFirst();
    }

    public Optional<Sku> findByCode(String code) {
        return jdbc.query(SELECT + " WHERE s.code = :c", Map.of("c", code), MAPPER).stream().findFirst();
    }

    /** Работы, разрешённые конфигурацией проекта (US-A3). */
    public List<Sku> findByProject(Long projectId) {
        return jdbc.query(SELECT + """
                JOIN project_config_skus pcs ON pcs.sku_id = s.id
                JOIN projects p ON p.config_id = pcs.config_id
                WHERE p.id = :p AND s.is_active = TRUE
                ORDER BY pcs.sort_order, s.name
                """, Map.of("p", projectId), MAPPER);
    }

    public List<Sku> findByConfig(Long configId) {
        return jdbc.query(SELECT + """
                JOIN project_config_skus pcs ON pcs.sku_id = s.id
                WHERE pcs.config_id = :c
                ORDER BY pcs.sort_order, s.name
                """, Map.of("c", configId), MAPPER);
    }

    public Sku create(Sku s) {
        var kh = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO sku (code, name, group_id, unit_code, act_wording, requires_photo,
                                 is_active, description, sort_order)
                VALUES (:code, :name, :gid, :unit, :act, :photo, :active, :descr, :ord)
                """, params(s), kh, new String[]{"id"});
        return findById(((Number) kh.getKeys().get("id")).longValue()).orElseThrow();
    }

    public Sku update(Long id, Sku s) {
        jdbc.update("""
                UPDATE sku SET code = :code, name = :name, group_id = :gid, unit_code = :unit,
                       act_wording = :act, requires_photo = :photo, is_active = :active,
                       description = :descr, sort_order = :ord
                WHERE id = :id
                """, params(s).addValue("id", id));
        return findById(id).orElseThrow();
    }

    private MapSqlParameterSource params(Sku s) {
        return new MapSqlParameterSource()
                .addValue("code", s.code())
                .addValue("name", s.name())
                .addValue("gid", s.groupId())
                .addValue("unit", s.unitCode())
                .addValue("act", s.actWording())
                .addValue("photo", s.requiresPhoto())
                .addValue("active", s.isActive())
                .addValue("descr", s.description())
                .addValue("ord", s.sortOrder());
    }

    public List<Map<String, Object>> groups() {
        return jdbc.queryForList("SELECT * FROM sku_groups ORDER BY sort_order, name", Map.of());
    }

    public void createGroup(String name, Integer sortOrder) {
        jdbc.update("INSERT INTO sku_groups (name, sort_order) VALUES (:n, :s)",
                Map.of("n", name, "s", sortOrder != null ? sortOrder : 100));
    }

    /** Используется ли работа в фактических данных — отвечает на вопрос «можно ли удалить». */
    public boolean isUsed(Long id) {
        Long c = jdbc.queryForObject(
                "SELECT (SELECT COUNT(*) FROM request_items WHERE sku_id = :id)"
                + " + (SELECT COUNT(*) FROM payroll_entries WHERE sku_id = :id)",
                Map.of("id", id), Long.class);
        return c != null && c > 0;
    }
}
