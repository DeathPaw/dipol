package ru.dipol.repository;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import ru.dipol.model.AppUser;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;

@Repository
public class AppUserRepository {

    private final NamedParameterJdbcTemplate jdbc;

    private static final RowMapper<AppUser> MAPPER = (rs, n) -> new AppUser(
            rs.getLong("id"),
            rs.getString("username"),
            rs.getString("password_hash"),
            rs.getString("display_name"),
            rs.getString("role"),
            rs.getObject("employee_id", Long.class),
            rs.getBoolean("is_active"),
            rs.getString("pin_code"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at").toLocalDateTime()
    );

    public AppUserRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AppUser> findByUsername(String username) {
        return jdbc.query("SELECT * FROM users WHERE username = :u AND is_active = TRUE",
                Map.of("u", username), MAPPER).stream().findFirst();
    }

    public Optional<AppUser> findById(Long id) {
        return jdbc.query("SELECT * FROM users WHERE id = :id", Map.of("id", id), MAPPER)
                .stream().findFirst();
    }

    /** Вход рабочего в мобильный кабинет по PIN — без логина и пароля. */
    public Optional<AppUser> findByPin(String pin) {
        return jdbc.query("SELECT * FROM users WHERE pin_code = :p AND is_active = TRUE AND role = 'WORKER'",
                Map.of("p", pin), MAPPER).stream().findFirst();
    }

    public List<AppUser> findAll() {
        return jdbc.query("SELECT * FROM users ORDER BY id", MAPPER);
    }

    public AppUser create(String username, String passwordHash, String displayName,
                          String role, Long employeeId, String pin) {
        var params = new MapSqlParameterSource()
                .addValue("u", username).addValue("p", passwordHash)
                .addValue("d", displayName).addValue("r", role)
                .addValue("e", employeeId).addValue("pin", pin);
        var kh = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO users (username, password_hash, display_name, role, employee_id, pin_code)
                VALUES (:u, :p, :d, :r, :e, :pin)
                """, params, kh, new String[]{"id"});
        return findById(((Number) kh.getKeys().get("id")).longValue()).orElseThrow();
    }

    public AppUser update(Long id, String displayName, String role, Long employeeId,
                          boolean isActive, String pin) {
        var params = new MapSqlParameterSource()
                .addValue("d", displayName).addValue("r", role).addValue("e", employeeId)
                .addValue("a", isActive).addValue("pin", pin).addValue("id", id);
        jdbc.update("""
                UPDATE users SET display_name = :d, role = :r, employee_id = :e,
                       is_active = :a, pin_code = :pin, updated_at = NOW()
                WHERE id = :id
                """, params);
        return findById(id).orElseThrow();
    }

    public void updatePassword(Long id, String passwordHash) {
        jdbc.update("UPDATE users SET password_hash = :p, updated_at = NOW() WHERE id = :id",
                Map.of("p", passwordHash, "id", id));
    }

    public long count() {
        Long c = jdbc.queryForObject("SELECT COUNT(*) FROM users", Map.of(), Long.class);
        return c != null ? c : 0;
    }

    /**
     * Итоговый набор прав: права роли плюс индивидуально выданные минус
     * индивидуально отозванные (US-N1 — снабжение и расчёты выдаются точечно).
     */
    public Set<String> effectivePermissions(AppUser user) {
        List<String> codes = jdbc.queryForList("""
                SELECT permission_code FROM role_permissions WHERE role = :r
                UNION
                SELECT permission_code FROM user_permissions WHERE user_id = :u AND granted = TRUE
                EXCEPT
                SELECT permission_code FROM user_permissions WHERE user_id = :u AND granted = FALSE
                """, Map.of("r", user.role(), "u", user.id()), String.class);
        return new HashSet<>(codes);
    }

    public List<Map<String, Object>> permissionCatalog() {
        return jdbc.queryForList("SELECT code, name, section FROM permissions ORDER BY section, code", Map.of());
    }

    public List<Map<String, Object>> userPermissions(Long userId) {
        return jdbc.queryForList(
                "SELECT permission_code, granted FROM user_permissions WHERE user_id = :u",
                Map.of("u", userId));
    }

    public void setUserPermission(Long userId, String code, Boolean granted) {
        if (granted == null) {
            jdbc.update("DELETE FROM user_permissions WHERE user_id = :u AND permission_code = :c",
                    Map.of("u", userId, "c", code));
            return;
        }
        jdbc.update("""
                INSERT INTO user_permissions (user_id, permission_code, granted)
                VALUES (:u, :c, :g)
                ON CONFLICT (user_id, permission_code) DO UPDATE SET granted = EXCLUDED.granted
                """, Map.of("u", userId, "c", code, "g", granted));
    }
}
