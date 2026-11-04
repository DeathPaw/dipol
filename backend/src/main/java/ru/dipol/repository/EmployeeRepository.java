package ru.dipol.repository;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import ru.dipol.model.Employee;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class EmployeeRepository {

    private static final String SELECT = """
            SELECT e.*, r.name AS role_name,
                   COALESCE((SELECT array_agg(s.sku_id) FROM employee_specializations s
                             WHERE s.employee_id = e.id), '{}') AS sku_ids
            FROM employees e LEFT JOIN employee_roles r ON r.code = e.role_code
            """;

    private static final RowMapper<Employee> MAPPER = (rs, n) -> {
        Long[] skus = (Long[]) rs.getArray("sku_ids").getArray();
        return new Employee(
                rs.getLong("id"), rs.getString("full_name"), rs.getString("short_name"),
                rs.getString("role_code"), rs.getString("role_name"), rs.getString("position"),
                rs.getString("phone"), rs.getString("email"),
                rs.getDate("hired_on") != null ? rs.getDate("hired_on").toLocalDate() : null,
                rs.getDate("fired_on") != null ? rs.getDate("fired_on").toLocalDate() : null,
                rs.getBoolean("is_active"), rs.getString("note"), List.of(skus));
    };

    private final NamedParameterJdbcTemplate jdbc;

    public EmployeeRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Employee> findAll(boolean includeInactive, String roleCode) {
        StringBuilder sql = new StringBuilder(SELECT + " WHERE 1=1");
        var p = new MapSqlParameterSource();
        if (!includeInactive) sql.append(" AND e.is_active = TRUE");
        if (roleCode != null && !roleCode.isBlank()) {
            sql.append(" AND e.role_code = :r");
            p.addValue("r", roleCode);
        }
        sql.append(" ORDER BY e.full_name");
        return jdbc.query(sql.toString(), p, MAPPER);
    }

    public Optional<Employee> findById(Long id) {
        return jdbc.query(SELECT + " WHERE e.id = :id", Map.of("id", id), MAPPER).stream().findFirst();
    }

    /**
     * Поиск по имени, как оно записано в действующих таблицах: там встречаются
     * и «Александр Кравченко», и просто «Мухтар». Поэтому сверяем и полное имя,
     * и короткое.
     */
    public Optional<Employee> findByAnyName(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        return jdbc.query(SELECT + """
                 WHERE lower(e.full_name) = lower(:n) OR lower(e.short_name) = lower(:n)
                 LIMIT 1
                """, Map.of("n", name.trim()), MAPPER).stream().findFirst();
    }

    public Employee create(Employee e) {
        var kh = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO employees (full_name, short_name, role_code, position, phone, email,
                                       hired_on, fired_on, is_active, note)
                VALUES (:full, :short, :role, :pos, :phone, :email, :hired, :fired, :active, :note)
                """, params(e), kh, new String[]{"id"});
        Long id = ((Number) kh.getKeys().get("id")).longValue();
        saveSpecializations(id, e.skuIds());
        return findById(id).orElseThrow();
    }

    public Employee update(Long id, Employee e) {
        jdbc.update("""
                UPDATE employees SET full_name = :full, short_name = :short, role_code = :role,
                       position = :pos, phone = :phone, email = :email, hired_on = :hired,
                       fired_on = :fired, is_active = :active, note = :note, updated_at = NOW()
                WHERE id = :id
                """, params(e).addValue("id", id));
        saveSpecializations(id, e.skuIds());
        return findById(id).orElseThrow();
    }

    private void saveSpecializations(Long employeeId, List<Long> skuIds) {
        if (skuIds == null) return;
        jdbc.update("DELETE FROM employee_specializations WHERE employee_id = :e", Map.of("e", employeeId));
        for (Long skuId : skuIds) {
            jdbc.update("INSERT INTO employee_specializations (employee_id, sku_id) VALUES (:e, :s)",
                    Map.of("e", employeeId, "s", skuId));
        }
    }

    private MapSqlParameterSource params(Employee e) {
        return new MapSqlParameterSource()
                .addValue("full", e.fullName()).addValue("short", e.shortName())
                .addValue("role", e.roleCode()).addValue("pos", e.position())
                .addValue("phone", e.phone()).addValue("email", e.email())
                .addValue("hired", e.hiredOn()).addValue("fired", e.firedOn())
                .addValue("active", e.isActive()).addValue("note", e.note());
    }

    // ---- шаблоны состава ----------------------------------------------------

    public List<Map<String, Object>> crewTemplates() {
        return jdbc.queryForList("""
                SELECT c.id, c.name, c.is_active,
                       COALESCE((SELECT array_agg(m.employee_id) FROM crew_template_members m
                                 WHERE m.crew_id = c.id), '{}') AS employee_ids
                FROM crew_templates c ORDER BY c.name
                """, Map.of());
    }

    public Long createCrewTemplate(String name, List<Long> employeeIds) {
        var kh = new GeneratedKeyHolder();
        jdbc.update("INSERT INTO crew_templates (name) VALUES (:n)",
                new MapSqlParameterSource("n", name), kh, new String[]{"id"});
        Long id = ((Number) kh.getKeys().get("id")).longValue();
        if (employeeIds != null) {
            for (Long e : employeeIds) {
                jdbc.update("INSERT INTO crew_template_members (crew_id, employee_id) VALUES (:c, :e)",
                        Map.of("c", id, "e", e));
            }
        }
        return id;
    }
}
