package ru.dipol.repository;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import ru.dipol.model.ProjectConfig;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class ProjectConfigRepository {

    private static final String SELECT = """
            SELECT c.*, (SELECT COUNT(*) FROM projects p WHERE p.config_id = c.id) AS projects_count
            FROM project_configs c
            """;

    private static final RowMapper<ProjectConfig> MAPPER = (rs, n) -> new ProjectConfig(
            rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getString("description"),
            rs.getBoolean("use_work_objects"), rs.getString("default_lifecycle"),
            splitSchemes(rs.getString("allowed_pay_schemes")),
            rs.getString("materials_mode"), rs.getInt("current_version"),
            rs.getBoolean("is_active"), rs.getInt("projects_count"));

    private static List<String> splitSchemes(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private final NamedParameterJdbcTemplate jdbc;

    public ProjectConfigRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ProjectConfig> findAll(boolean includeInactive) {
        return jdbc.query(SELECT + (includeInactive ? "" : " WHERE c.is_active = TRUE")
                + " ORDER BY c.name", MAPPER);
    }

    public Optional<ProjectConfig> findById(Long id) {
        return jdbc.query(SELECT + " WHERE c.id = :id", Map.of("id", id), MAPPER).stream().findFirst();
    }

    public Optional<ProjectConfig> findByCode(String code) {
        return jdbc.query(SELECT + " WHERE c.code = :c", Map.of("c", code), MAPPER).stream().findFirst();
    }

    public ProjectConfig create(ProjectConfig c, String author) {
        var kh = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO project_configs (code, name, description, use_work_objects, default_lifecycle,
                    allowed_pay_schemes, materials_mode, is_active, created_by)
                VALUES (:code, :name, :descr, :wo, :life, :schemes, :mat, :active, :author)
                """, params(c).addValue("author", author), kh, new String[]{"id"});
        Long id = ((Number) kh.getKeys().get("id")).longValue();
        jdbc.update("""
                INSERT INTO project_config_versions (config_id, version, author, comment)
                VALUES (:id, 1, :a, 'Создание конфигурации')
                """, Map.of("id", id, "a", author));
        return findById(id).orElseThrow();
    }

    public ProjectConfig update(Long id, ProjectConfig c) {
        jdbc.update("""
                UPDATE project_configs SET name = :name, description = :descr, use_work_objects = :wo,
                       default_lifecycle = :life, allowed_pay_schemes = :schemes, materials_mode = :mat,
                       is_active = :active, updated_at = NOW()
                WHERE id = :id
                """, params(c).addValue("id", id));
        return findById(id).orElseThrow();
    }

    /**
     * Новая версия правил. Закрытые заявки и начисления остаются на своей
     * версии — пересчёта задним числом не происходит (US-A3).
     */
    public int bumpVersion(Long id, String rulesJson, String author, String comment) {
        Integer next = jdbc.queryForObject(
                "UPDATE project_configs SET current_version = current_version + 1, updated_at = NOW()"
                + " WHERE id = :id RETURNING current_version", Map.of("id", id), Integer.class);
        jdbc.update("""
                INSERT INTO project_config_versions (config_id, version, rules_json, author, comment)
                VALUES (:id, :v, :r, :a, :c)
                """, Map.of("id", id, "v", next, "r", rulesJson != null ? rulesJson : "",
                            "a", author, "c", comment != null ? comment : ""));
        return next != null ? next : 1;
    }

    public List<Map<String, Object>> versions(Long configId) {
        return jdbc.queryForList(
                "SELECT * FROM project_config_versions WHERE config_id = :c ORDER BY version DESC",
                Map.of("c", configId));
    }

    private MapSqlParameterSource params(ProjectConfig c) {
        return new MapSqlParameterSource()
                .addValue("code", c.code()).addValue("name", c.name())
                .addValue("descr", c.description()).addValue("wo", c.useWorkObjects())
                .addValue("life", c.defaultLifecycle())
                .addValue("schemes", String.join(",", c.allowedPaySchemes()))
                .addValue("mat", c.materialsMode()).addValue("active", c.isActive());
    }

    // ---- состав правил ------------------------------------------------------

    public List<Map<String, Object>> skus(Long configId) {
        return jdbc.queryForList("""
                SELECT s.id, s.code, s.name, s.unit_code, pcs.is_default, pcs.sort_order
                FROM project_config_skus pcs JOIN sku s ON s.id = pcs.sku_id
                WHERE pcs.config_id = :c ORDER BY pcs.sort_order, s.name
                """, Map.of("c", configId));
    }

    public void setSkus(Long configId, List<Long> skuIds) {
        jdbc.update("DELETE FROM project_config_skus WHERE config_id = :c", Map.of("c", configId));
        int order = 10;
        for (Long skuId : skuIds) {
            jdbc.update("""
                    INSERT INTO project_config_skus (config_id, sku_id, is_default, sort_order)
                    VALUES (:c, :s, TRUE, :o)
                    """, Map.of("c", configId, "s", skuId, "o", order));
            order += 10;
        }
    }

    public List<Map<String, Object>> transitions(Long configId) {
        return jdbc.queryForList(
                "SELECT from_status, to_status FROM project_config_transitions WHERE config_id = :c",
                Map.of("c", configId));
    }

    public void setTransitions(Long configId, List<Map<String, String>> transitions) {
        jdbc.update("DELETE FROM project_config_transitions WHERE config_id = :c", Map.of("c", configId));
        for (var t : transitions) {
            jdbc.update("""
                    INSERT INTO project_config_transitions (config_id, from_status, to_status)
                    VALUES (:c, :f, :t) ON CONFLICT DO NOTHING
                    """, Map.of("c", configId, "f", t.get("from_status"), "t", t.get("to_status")));
        }
    }

    public List<Map<String, Object>> fields(Long configId) {
        return jdbc.queryForList(
                "SELECT field_code, is_visible, is_required FROM project_config_fields WHERE config_id = :c"
                + " ORDER BY field_code", Map.of("c", configId));
    }

    public void setField(Long configId, String fieldCode, boolean visible, boolean required) {
        jdbc.update("""
                INSERT INTO project_config_fields (config_id, field_code, is_visible, is_required)
                VALUES (:c, :f, :v, :r)
                ON CONFLICT (config_id, field_code) DO UPDATE
                   SET is_visible = EXCLUDED.is_visible, is_required = EXCLUDED.is_required
                """, Map.of("c", configId, "f", fieldCode, "v", visible, "r", required));
    }

    public List<Map<String, Object>> checklist(Long configId) {
        return jdbc.queryForList(
                "SELECT id, item_code, name, is_required, sort_order FROM project_config_checklist"
                + " WHERE config_id = :c ORDER BY sort_order", Map.of("c", configId));
    }

    public void setChecklist(Long configId, List<Map<String, Object>> items) {
        jdbc.update("DELETE FROM project_config_checklist WHERE config_id = :c", Map.of("c", configId));
        int order = 10;
        for (var i : items) {
            jdbc.update("""
                    INSERT INTO project_config_checklist (config_id, item_code, name, is_required, sort_order)
                    VALUES (:c, :code, :name, :req, :o)
                    """, Map.of("c", configId, "code", i.get("item_code"), "name", i.get("name"),
                                "req", Boolean.TRUE.equals(i.get("is_required")), "o", order));
            order += 10;
        }
    }

    /**
     * Клонирование конфигурации со всеми правилами (US-O3): новое направление
     * запускается средствами ERP, без обращения к разработчику.
     */
    public Long clone(Long sourceId, String newCode, String newName, String author) {
        var kh = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO project_configs (code, name, description, use_work_objects, default_lifecycle,
                    allowed_pay_schemes, materials_mode, is_active, created_by)
                SELECT :code, :name, description, use_work_objects, default_lifecycle,
                       allowed_pay_schemes, materials_mode, FALSE, :author
                FROM project_configs WHERE id = :src
                """, new MapSqlParameterSource().addValue("code", newCode).addValue("name", newName)
                     .addValue("author", author).addValue("src", sourceId),
                kh, new String[]{"id"});
        Long id = ((Number) kh.getKeys().get("id")).longValue();
        Map<String, Object> p = Map.of("src", sourceId, "dst", id);
        jdbc.update("INSERT INTO project_config_skus (config_id, sku_id, is_default, sort_order)"
                + " SELECT :dst, sku_id, is_default, sort_order FROM project_config_skus WHERE config_id = :src", p);
        jdbc.update("INSERT INTO project_config_statuses (config_id, status_code, sort_order)"
                + " SELECT :dst, status_code, sort_order FROM project_config_statuses WHERE config_id = :src", p);
        jdbc.update("INSERT INTO project_config_transitions (config_id, from_status, to_status)"
                + " SELECT :dst, from_status, to_status FROM project_config_transitions WHERE config_id = :src", p);
        jdbc.update("INSERT INTO project_config_fields (config_id, field_code, is_visible, is_required)"
                + " SELECT :dst, field_code, is_visible, is_required FROM project_config_fields WHERE config_id = :src", p);
        jdbc.update("INSERT INTO project_config_checklist (config_id, item_code, name, is_required, sort_order)"
                + " SELECT :dst, item_code, name, is_required, sort_order FROM project_config_checklist WHERE config_id = :src", p);
        jdbc.update("""
                INSERT INTO project_config_versions (config_id, version, author, comment)
                VALUES (:dst, 1, :a, 'Клонирована из конфигурации id=' || :srcText)
                """, Map.of("dst", id, "a", author, "srcText", String.valueOf(sourceId)));
        return id;
    }

    /** Допустим ли переход статуса в рамках конфигурации. */
    public boolean transitionAllowed(Long configId, String from, String to) {
        Long c = jdbc.queryForObject("""
                SELECT COUNT(*) FROM project_config_transitions
                WHERE config_id = :c AND from_status = :f AND to_status = :t
                """, Map.of("c", configId, "f", from, "t", to), Long.class);
        return c != null && c > 0;
    }

    /** Чего не хватает конфигурации до активации (US-O3). */
    public List<String> validationIssues(Long configId) {
        List<String> issues = new java.util.ArrayList<>();
        if (skus(configId).isEmpty()) issues.add("Не выбрана ни одна работа/SKU");
        if (transitions(configId).isEmpty()) issues.add("Не задана статусная схема");
        if (checklist(configId).isEmpty()) issues.add("Не задан чек-лист закрытия заявки");
        return issues;
    }
}
