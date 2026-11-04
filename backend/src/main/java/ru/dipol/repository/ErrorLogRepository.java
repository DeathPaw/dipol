package ru.dipol.repository;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.dipol.model.ErrorLogEntry;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public class ErrorLogRepository {

    private final NamedParameterJdbcTemplate jdbc;

    private static final RowMapper<ErrorLogEntry> MAPPER = (rs, n) -> new ErrorLogEntry(
            rs.getLong("id"),
            rs.getString("error_type"),
            rs.getString("message"),
            rs.getString("request_path"),
            rs.getString("actor"),
            rs.getTimestamp("created_at").toLocalDateTime()
    );

    public ErrorLogRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(String errorType, String message, String requestPath, String actor) {
        Map<String, Object> p = new HashMap<>();
        p.put("t", errorType);
        p.put("m", message.length() > 4000 ? message.substring(0, 4000) : message);
        p.put("p", requestPath);
        p.put("a", actor);
        jdbc.update("INSERT INTO error_log (error_type, message, request_path, actor) VALUES (:t, :m, :p, :a)", p);
    }

    public List<ErrorLogEntry> findRecent(int limit) {
        return jdbc.query("SELECT * FROM error_log ORDER BY created_at DESC LIMIT :l",
                Map.of("l", limit), MAPPER);
    }

    public void clear() {
        jdbc.update("DELETE FROM error_log", Map.of());
    }
}
