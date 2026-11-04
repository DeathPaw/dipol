package ru.dipol.repository;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.dipol.model.AuditLogEntry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public class AuditLogRepository {

    private final NamedParameterJdbcTemplate jdbc;

    private static final RowMapper<AuditLogEntry> MAPPER = (rs, n) -> new AuditLogEntry(
            rs.getLong("id"),
            rs.getString("entity_type"),
            rs.getObject("entity_id", Long.class),
            rs.getString("action"),
            rs.getString("field"),
            rs.getString("old_value"),
            rs.getString("new_value"),
            rs.getString("comment"),
            rs.getString("actor"),
            rs.getTimestamp("created_at").toLocalDateTime()
    );

    public AuditLogRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void log(String entityType, Long entityId, String action, String field,
                    String oldValue, String newValue, String comment, String actor) {
        Map<String, Object> p = new HashMap<>();
        p.put("et", entityType);
        p.put("ei", entityId);
        p.put("a", action);
        p.put("f", field);
        p.put("ov", trim(oldValue));
        p.put("nv", trim(newValue));
        p.put("c", comment);
        p.put("ac", actor);
        jdbc.update("""
                INSERT INTO audit_log (entity_type, entity_id, action, field, old_value, new_value, comment, actor)
                VALUES (:et, :ei, :a, :f, :ov, :nv, :c, :ac)
                """, p);
    }

    public List<AuditLogEntry> find(String entityType, Long entityId, String actor, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM audit_log WHERE 1=1");
        Map<String, Object> p = new HashMap<>();
        if (entityType != null && !entityType.isBlank()) {
            sql.append(" AND entity_type = :et");
            p.put("et", entityType);
        }
        if (entityId != null) {
            sql.append(" AND entity_id = :ei");
            p.put("ei", entityId);
        }
        if (actor != null && !actor.isBlank()) {
            sql.append(" AND actor ILIKE :ac");
            p.put("ac", "%" + actor + "%");
        }
        sql.append(" ORDER BY created_at DESC LIMIT :l");
        p.put("l", limit);
        return jdbc.query(sql.toString(), p, MAPPER);
    }

    /** Список типов сущностей, по которым есть записи — для фильтра в интерфейсе. */
    public List<String> entityTypes() {
        return new ArrayList<>(jdbc.queryForList(
                "SELECT DISTINCT entity_type FROM audit_log ORDER BY 1", Map.of(), String.class));
    }

    private static String trim(String v) {
        if (v == null) return null;
        return v.length() > 2000 ? v.substring(0, 2000) : v;
    }
}
