package ru.dipol.repository;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import ru.dipol.model.Project;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class ProjectRepository {

    private static final String SELECT = """
            SELECT p.*, cl.name AS client_name, cf.code AS config_code, cf.name AS config_name,
                   e.full_name AS responsible_name
            FROM projects p
            JOIN clients cl ON cl.id = p.client_id
            JOIN project_configs cf ON cf.id = p.config_id
            LEFT JOIN employees e ON e.id = p.responsible_employee_id
            """;

    private static final RowMapper<Project> MAPPER = (rs, n) -> new Project(
            rs.getLong("id"), rs.getString("code"), rs.getString("name"),
            rs.getLong("client_id"), rs.getString("client_name"),
            rs.getLong("config_id"), rs.getString("config_code"), rs.getString("config_name"),
            rs.getInt("config_version"), rs.getString("lifecycle_type"),
            date(rs.getDate("started_on")), rs.getString("finish_criteria"),
            date(rs.getDate("finished_on")), rs.getString("status"),
            rs.getObject("responsible_employee_id", Long.class), rs.getString("responsible_name"),
            date(rs.getDate("season_from")), date(rs.getDate("season_to")),
            rs.getString("contract_number"), rs.getString("note"));

    private static LocalDate date(java.sql.Date d) {
        return d != null ? d.toLocalDate() : null;
    }

    private final NamedParameterJdbcTemplate jdbc;

    public ProjectRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Project> findAll(String status, Long clientId, Long configId) {
        StringBuilder sql = new StringBuilder(SELECT + " WHERE 1=1");
        Map<String, Object> p = new HashMap<>();
        if (status != null && !status.isBlank()) {
            sql.append(" AND p.status = :s");
            p.put("s", status);
        }
        if (clientId != null) {
            sql.append(" AND p.client_id = :c");
            p.put("c", clientId);
        }
        if (configId != null) {
            sql.append(" AND p.config_id = :cf");
            p.put("cf", configId);
        }
        sql.append(" ORDER BY p.name");
        return jdbc.query(sql.toString(), p, MAPPER);
    }

    public Optional<Project> findById(Long id) {
        return jdbc.query(SELECT + " WHERE p.id = :id", Map.of("id", id), MAPPER).stream().findFirst();
    }

    public Optional<Project> findByCode(String code) {
        return jdbc.query(SELECT + " WHERE p.code = :c", Map.of("c", code), MAPPER).stream().findFirst();
    }

    public Project create(Project p) {
        var kh = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO projects (code, name, client_id, config_id, config_version, lifecycle_type,
                    started_on, finish_criteria, finished_on, status, responsible_employee_id,
                    season_from, season_to, contract_number, note, created_by)
                VALUES (:code, :name, :client, :config,
                        COALESCE((SELECT current_version FROM project_configs WHERE id = :config), 1),
                        :life, :start, :crit, :fin, :status, :resp, :sfrom, :sto, :contract, :note, :author)
                """, params(p).addValue("author", ru.dipol.audit.CurrentUser.name()), kh, new String[]{"id"});
        return findById(((Number) kh.getKeys().get("id")).longValue()).orElseThrow();
    }

    public Project update(Long id, Project p) {
        jdbc.update("""
                UPDATE projects SET name = :name, client_id = :client, lifecycle_type = :life,
                       started_on = :start, finish_criteria = :crit, finished_on = :fin,
                       status = :status, responsible_employee_id = :resp, season_from = :sfrom,
                       season_to = :sto, contract_number = :contract, note = :note, updated_at = NOW()
                WHERE id = :id
                """, params(p).addValue("id", id));
        return findById(id).orElseThrow();
    }

    public void changeStatus(Long id, String from, String to, String reason, String actor) {
        jdbc.update("UPDATE projects SET status = :s, updated_at = NOW() WHERE id = :id",
                Map.of("s", to, "id", id));
        Map<String, Object> p = new HashMap<>();
        p.put("id", id);
        p.put("f", from);
        p.put("t", to);
        p.put("r", reason);
        p.put("a", actor);
        jdbc.update("""
                INSERT INTO project_status_history (project_id, from_status, to_status, reason, actor)
                VALUES (:id, :f, :t, :r, :a)
                """, p);
    }

    /** Перевод проекта на новую версию конфигурации — контролируемая операция (US-O4). */
    public void applyConfigVersion(Long projectId, int version) {
        jdbc.update("UPDATE projects SET config_version = :v, updated_at = NOW() WHERE id = :id",
                Map.of("v", version, "id", projectId));
    }

    private MapSqlParameterSource params(Project p) {
        return new MapSqlParameterSource()
                .addValue("code", p.code()).addValue("name", p.name())
                .addValue("client", p.clientId()).addValue("config", p.configId())
                .addValue("life", p.lifecycleType()).addValue("start", p.startedOn())
                .addValue("crit", p.finishCriteria()).addValue("fin", p.finishedOn())
                .addValue("status", p.status()).addValue("resp", p.responsibleEmployeeId())
                .addValue("sfrom", p.seasonFrom()).addValue("sto", p.seasonTo())
                .addValue("contract", p.contractNumber()).addValue("note", p.note());
    }

    // ---- месячный план ------------------------------------------------------

    public List<Map<String, Object>> monthlyPlans(Long projectId) {
        return jdbc.queryForList(
                "SELECT * FROM project_monthly_plans WHERE project_id = :p ORDER BY period_month DESC",
                Map.of("p", projectId));
    }

    public void saveMonthlyPlan(Long projectId, LocalDate month, java.math.BigDecimal payroll,
                                java.math.BigDecimal materials, java.math.BigDecimal volume,
                                String volumeUnit, String note) {
        Map<String, Object> p = new HashMap<>();
        p.put("p", projectId);
        p.put("m", month);
        p.put("pay", payroll);
        p.put("mat", materials);
        p.put("vol", volume);
        p.put("unit", volumeUnit);
        p.put("note", note);
        jdbc.update("""
                INSERT INTO project_monthly_plans (project_id, period_month, payroll_plan, materials_plan,
                                                   volume_plan, volume_unit, note)
                VALUES (:p, :m, :pay, :mat, :vol, :unit, :note)
                ON CONFLICT (project_id, period_month) DO UPDATE SET
                    payroll_plan = EXCLUDED.payroll_plan, materials_plan = EXCLUDED.materials_plan,
                    volume_plan = EXCLUDED.volume_plan, volume_unit = EXCLUDED.volume_unit,
                    note = EXCLUDED.note
                """, p);
    }

    public List<Map<String, Object>> buildings(Long projectId) {
        return jdbc.queryForList("""
                SELECT b.id, b.address, b.legacy_id FROM project_buildings pb
                JOIN buildings b ON b.id = pb.building_id
                WHERE pb.project_id = :p ORDER BY b.address
                """, Map.of("p", projectId));
    }

    public void setBuildings(Long projectId, List<Long> buildingIds) {
        jdbc.update("DELETE FROM project_buildings WHERE project_id = :p", Map.of("p", projectId));
        for (Long b : buildingIds) {
            jdbc.update("INSERT INTO project_buildings (project_id, building_id) VALUES (:p, :b)",
                    Map.of("p", projectId, "b", b));
        }
    }

    public Map<String, Object> summary(Long projectId) {
        return jdbc.queryForMap("""
                SELECT
                  (SELECT COUNT(*) FROM requests r WHERE r.project_id = :p) AS requests_total,
                  (SELECT COUNT(*) FROM requests r WHERE r.project_id = :p
                      AND r.status NOT IN ('ACT_SIGNED','CANCELLED')) AS requests_open,
                  (SELECT COALESCE(SUM(pe.amount),0) FROM payroll_entries pe
                      WHERE pe.project_id = :p AND pe.status <> 'REVERSED') AS payroll_total,
                  (SELECT COALESCE(SUM(sm.amount),0) FROM stock_movements sm
                      WHERE sm.project_id = :p AND sm.movement_type = 'ISSUE') AS materials_total,
                  (SELECT COUNT(DISTINCT r.building_id) FROM requests r WHERE r.project_id = :p) AS buildings_count
                """, Map.of("p", projectId));
    }
}
