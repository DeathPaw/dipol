package ru.dipol.controller;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.web.bind.annotation.*;
import ru.dipol.audit.CurrentUser;
import ru.dipol.exception.BusinessRuleException;
import ru.dipol.repository.ParameterRepository;
import ru.dipol.service.AuditService;
import ru.dipol.service.PayrollService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * РК-3: очередь домов, дефектовка, объекты большого ремонта и расчёт вырубки.
 *
 * <p>Ключевое отличие от legacy-таблицы: выплата вырубки считается как
 * часы × базовая ставка × k, где k выбирается из версионируемых диапазонов.
 * Подстановка p вместо k (ошибка действующего реестра) здесь невозможна —
 * расчётом занимается {@link PayrollService#calcDemolition}.
 */
@RestController
@RequestMapping("/api/rk3")
public class Rk3Controller {

    private final NamedParameterJdbcTemplate jdbc;
    private final PayrollService payroll;
    private final ParameterRepository parameters;
    private final AuditService audit;

    public Rk3Controller(NamedParameterJdbcTemplate jdbc, PayrollService payroll,
                         ParameterRepository parameters, AuditService audit) {
        this.jdbc = jdbc;
        this.payroll = payroll;
        this.parameters = parameters;
        this.audit = audit;
    }

    // ---- очередь домов ------------------------------------------------------

    @GetMapping("/queue")
    public List<Map<String, Object>> queue(@RequestParam(required = false) Integer year,
                                           @RequestParam(required = false) String status) {
        StringBuilder sql = new StringBuilder("""
                SELECT q.*, b.address, b.legacy_id AS building_legacy_id, b.roof_type AS building_roof_type,
                       z.name AS zheu_name
                FROM rk3_queue q
                JOIN buildings b ON b.id = q.building_id
                LEFT JOIN zheu z ON z.id = b.zheu_id
                WHERE 1=1
                """);
        Map<String, Object> p = new HashMap<>();
        if (year != null) {
            sql.append(" AND q.season_year = :y");
            p.put("y", year);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND q.status = :s");
            p.put("s", status);
        }
        sql.append(" ORDER BY q.priority DESC, q.requests_active DESC NULLS LAST, b.address");
        return jdbc.queryForList(sql.toString(), p);
    }

    /**
     * Пересчёт очереди из первичных данных: активные заявки по подъездам,
     * повторы, история локремонтов. Отдельного устаревающего «Свода» нет —
     * цифры берутся из реестра заявок (US-F1).
     */
    @PostMapping("/queue/rebuild")
    public Map<String, Object> rebuildQueue(@RequestParam int year,
                                            @RequestParam(required = false) Long projectId) {
        BigDecimal areaPerEntrance = parameters.numberOn("RK3_AREA_PER_ENTRANCE", LocalDate.now());
        BigDecimal demolitionWidth = parameters.numberOn("RK3_DEMOLITION_WIDTH", LocalDate.now());

        List<Map<String, Object>> stats = jdbc.queryForList("""
                SELECT r.building_id,
                       COUNT(*) AS requests_total,
                       COUNT(*) FILTER (WHERE r.status NOT IN ('ACT_SIGNED','CANCELLED')) AS requests_active,
                       COUNT(*) FILTER (WHERE r.link_type IN ('REPEAT','WARRANTY')) AS repeats,
                       COUNT(DISTINCT r.entrance) AS entrances_touched,
                       string_agg(DISTINCT r.apartment, ', ') FILTER (WHERE r.link_type IS NOT NULL)
                           AS repeat_apartments
                FROM requests r
                WHERE EXTRACT(YEAR FROM r.received_at) >= :y - 1
                GROUP BY r.building_id
                """, Map.of("y", year));

        int updated = 0;
        for (var s : stats) {
            Long buildingId = ((Number) s.get("building_id")).longValue();
            var building = jdbc.queryForMap(
                    "SELECT entrances_count, roof_type FROM buildings WHERE id = :b",
                    Map.of("b", buildingId));
            Integer entrances = building.get("entrances_count") != null
                    ? ((Number) building.get("entrances_count")).intValue() : null;

            Map<String, Object> p = new HashMap<>();
            p.put("b", buildingId);
            p.put("proj", projectId);
            p.put("y", year);
            p.put("ec", entrances);
            p.put("rt", s.get("requests_total"));
            p.put("ra", s.get("requests_active"));
            p.put("rep", s.get("repeat_apartments"));
            p.put("roof", building.get("roof_type"));
            // Предварительный объём — только подсказка от нормативов; утверждённое
            // решение по подъездам прораб вносит отдельно.
            p.put("est", areaPerEntrance != null && entrances != null
                    ? areaPerEntrance.multiply(BigDecimal.valueOf(entrances)) : null);
            p.put("estd", demolitionWidth);
            p.put("ver", "area_per_entrance=" + areaPerEntrance + "; width=" + demolitionWidth);
            jdbc.update("""
                    INSERT INTO rk3_queue (building_id, project_id, season_year, entrances_count,
                        requests_total, requests_active, repeat_apartments, roof_type,
                        estimated_roof_m2, demolition_width_m, algorithm_version, calculated_at)
                    VALUES (:b, :proj, :y, :ec, :rt, :ra, :rep, :roof, :est, :estd, :ver, NOW())
                    ON CONFLICT (building_id, season_year) DO UPDATE SET
                        requests_total = EXCLUDED.requests_total,
                        requests_active = EXCLUDED.requests_active,
                        repeat_apartments = EXCLUDED.repeat_apartments,
                        entrances_count = EXCLUDED.entrances_count,
                        estimated_roof_m2 = EXCLUDED.estimated_roof_m2,
                        algorithm_version = EXCLUDED.algorithm_version,
                        calculated_at = NOW()
                    """, p);
            updated++;
        }
        audit.action("RK3_QUEUE", null, "REBUILD", "год " + year + ", домов: " + updated);
        Map<String, Object> result = new HashMap<>();
        result.put("updated", updated);
        result.put("area_per_entrance", areaPerEntrance);
        result.put("demolition_width", demolitionWidth);
        if (areaPerEntrance == null) {
            result.put("warning", "Норматив площади на подъезд не утверждён (вопрос Q-02) — "
                    + "предварительный объём не рассчитан");
        }
        return result;
    }

    /** Утверждённое решение по подъездам хранится отдельно от рекомендации. */
    @PutMapping("/queue/{id}")
    public Map<String, Object> updateQueue(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", id);
        p.put("ap", body.get("approved_entrances"));
        p.put("wd", Boolean.TRUE.equals(body.get("with_demolition")));
        p.put("w", dec(body.get("demolition_width_m")));
        p.put("ed", dec(body.get("estimated_demolition_m2")));
        p.put("er", dec(body.get("estimated_roof_m2")));
        p.put("st", body.get("status"));
        p.put("pr", body.get("priority") != null ? ((Number) body.get("priority")).intValue() : 0);
        p.put("prr", body.get("priority_reason"));
        jdbc.update("""
                UPDATE rk3_queue SET approved_entrances = :ap, with_demolition = :wd,
                       demolition_width_m = :w, estimated_demolition_m2 = :ed, estimated_roof_m2 = :er,
                       status = :st, priority = :pr, priority_reason = :prr
                WHERE id = :id
                """, p);
        audit.action("RK3_QUEUE", id, "UPDATE", "подъезды: " + body.get("approved_entrances"));
        return Map.of("status", "updated");
    }

    // ---- дефектовка ---------------------------------------------------------

    @GetMapping("/defect-surveys")
    public List<Map<String, Object>> surveys(@RequestParam(required = false) Long buildingId) {
        StringBuilder sql = new StringBuilder("""
                SELECT d.*, b.address, e.full_name AS author_name
                FROM defect_surveys d
                JOIN buildings b ON b.id = d.building_id
                LEFT JOIN employees e ON e.id = d.author_employee_id
                WHERE 1=1
                """);
        Map<String, Object> p = new HashMap<>();
        if (buildingId != null) {
            sql.append(" AND d.building_id = :b");
            p.put("b", buildingId);
        }
        sql.append(" ORDER BY d.survey_date DESC");
        return jdbc.queryForList(sql.toString(), p);
    }

    @PostMapping("/defect-surveys")
    public Map<String, Object> createSurvey(@RequestBody Map<String, Object> body) {
        var kh = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO defect_surveys (building_id, project_id, survey_date, author_employee_id,
                    description, entrances, estimated_roof_m2, estimated_demolition_m2, calc_formula,
                    manual_override_reason)
                VALUES (:b, :p, :d, :a, :descr, :e, :er, :ed, :f, :r)
                """, new MapSqlParameterSource()
                        .addValue("b", num(body.get("building_id")))
                        .addValue("p", num(body.get("project_id")))
                        .addValue("d", LocalDate.parse(String.valueOf(body.get("survey_date"))))
                        .addValue("a", num(body.get("author_employee_id")))
                        .addValue("descr", body.get("description"))
                        .addValue("e", body.get("entrances"))
                        .addValue("er", dec(body.get("estimated_roof_m2")))
                        .addValue("ed", dec(body.get("estimated_demolition_m2")))
                        .addValue("f", body.get("calc_formula"))
                        .addValue("r", body.get("manual_override_reason")),
                kh, new String[]{"id"});
        Long id = ((Number) kh.getKeys().get("id")).longValue();
        audit.created("DEFECT_SURVEY", id);
        return Map.of("id", id);
    }

    // ---- объекты РК-3 -------------------------------------------------------

    @GetMapping("/objects")
    public List<Map<String, Object>> objects(@RequestParam(required = false) Integer year,
                                             @RequestParam(required = false) Long projectId,
                                             @RequestParam(required = false) String status) {
        StringBuilder sql = new StringBuilder("""
                SELECT o.*, b.address, p.code AS project_code
                FROM rk3_objects o
                JOIN buildings b ON b.id = o.building_id
                JOIN projects p ON p.id = o.project_id
                WHERE 1=1
                """);
        Map<String, Object> pr = new HashMap<>();
        if (year != null) {
            sql.append(" AND o.season_year = :y");
            pr.put("y", year);
        }
        if (projectId != null) {
            sql.append(" AND o.project_id = :p");
            pr.put("p", projectId);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND o.status = :s");
            pr.put("s", status);
        }
        sql.append(" ORDER BY o.season_year DESC, o.seq_in_year");
        return jdbc.queryForList(sql.toString(), pr);
    }

    @GetMapping("/objects/{id}")
    public Map<String, Object> object(@PathVariable Long id) {
        return Map.of(
                "object", jdbc.queryForMap("""
                        SELECT o.*, b.address, p.code AS project_code
                        FROM rk3_objects o JOIN buildings b ON b.id = o.building_id
                        JOIN projects p ON p.id = o.project_id WHERE o.id = :id
                        """, Map.of("id", id)),
                "lines", jdbc.queryForList("""
                        SELECT l.*, m.name AS material_name FROM rk3_object_lines l
                        LEFT JOIN materials m ON m.id = l.material_id
                        WHERE l.rk3_object_id = :id ORDER BY l.line_kind, l.sort_order
                        """, Map.of("id", id)),
                "corrections", jdbc.queryForList(
                        "SELECT * FROM rk3_corrections WHERE rk3_object_id = :id ORDER BY created_at DESC",
                        Map.of("id", id))
        );
    }

    @PostMapping("/objects")
    public Map<String, Object> createObject(@RequestBody Map<String, Object> body) {
        var kh = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO rk3_objects (act_number, project_id, building_id, seq_in_year, season_year,
                    defect_survey_id, defect_date, crew_roofers, crew_demolition, note, created_by)
                VALUES (:act, :p, :b, :seq, :y, :ds, :dd, :cr, :cd, :note, :author)
                """, new MapSqlParameterSource()
                        .addValue("act", body.get("act_number"))
                        .addValue("p", num(body.get("project_id")))
                        .addValue("b", num(body.get("building_id")))
                        .addValue("seq", body.get("seq_in_year"))
                        .addValue("y", body.get("season_year"))
                        .addValue("ds", num(body.get("defect_survey_id")))
                        .addValue("dd", date(body.get("defect_date")))
                        .addValue("cr", body.get("crew_roofers"))
                        .addValue("cd", body.get("crew_demolition"))
                        .addValue("note", body.get("note"))
                        .addValue("author", CurrentUser.name()),
                kh, new String[]{"id"});
        Long id = ((Number) kh.getKeys().get("id")).longValue();
        audit.created("RK3_OBJECT", id);
        return Map.of("id", id);
    }

    @PutMapping("/objects/{id}")
    public Map<String, Object> updateObject(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        var before = jdbc.queryForMap("SELECT * FROM rk3_objects WHERE id = :id", Map.of("id", id));
        // Изменение принятого объёма после закрытия — не правка, а корректировка
        // с историей (US-F5).
        if ("CLOSED".equals(String.valueOf(before.get("status")))) {
            String reason = (String) body.get("correction_reason");
            if (reason == null || reason.isBlank()) {
                throw new BusinessRuleException(
                        "Объект закрыт: изменение принятого объёма выполняется корректировкой с причиной");
            }
            Map<String, Object> c = new HashMap<>();
            c.put("o", id);
            c.put("f", "accepted_demolition_m2");
            c.put("ov", String.valueOf(before.get("accepted_demolition_m2")));
            c.put("nv", String.valueOf(body.get("accepted_demolition_m2")));
            c.put("r", reason);
            c.put("a", CurrentUser.name());
            jdbc.update("""
                    INSERT INTO rk3_corrections (rk3_object_id, field, old_value, new_value, reason, author)
                    VALUES (:o, :f, :ov, :nv, :r, :a)
                    """, c);
        }

        jdbc.update("""
                UPDATE rk3_objects SET shipment_date = :sd, supplier_order_number = :son,
                    delivery_cost = :dc, unload_cost = :uc, lift_method = :lm, lift_cost = :lc,
                    waste_cost = :wc, shipment_total = :st, crew_roofers = :cr, crew_demolition = :cd,
                    accepted_at = :aa, accepted_demolition_m2 = :adm, fact_demolition_man_hours = :fmh,
                    customer_rep = :crep, contractor_rep = :conrep, status = :status, note = :note,
                    updated_at = NOW()
                WHERE id = :id
                """, new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("sd", date(body.get("shipment_date")))
                        .addValue("son", body.get("supplier_order_number"))
                        .addValue("dc", dec(body.get("delivery_cost")))
                        .addValue("uc", dec(body.get("unload_cost")))
                        .addValue("lm", body.get("lift_method"))
                        .addValue("lc", dec(body.get("lift_cost")))
                        .addValue("wc", dec(body.get("waste_cost")))
                        .addValue("st", dec(body.get("shipment_total")))
                        .addValue("cr", body.get("crew_roofers"))
                        .addValue("cd", body.get("crew_demolition"))
                        .addValue("aa", date(body.get("accepted_at")))
                        .addValue("adm", dec(body.get("accepted_demolition_m2")))
                        .addValue("fmh", dec(body.get("fact_demolition_man_hours")))
                        .addValue("crep", body.get("customer_rep"))
                        .addValue("conrep", body.get("contractor_rep"))
                        .addValue("status", body.getOrDefault("status", before.get("status")))
                        .addValue("note", body.get("note")));
        audit.action("RK3_OBJECT", id, "UPDATE", null);
        return object(id);
    }

    @PutMapping("/objects/{id}/lines")
    public Map<String, Object> setLines(@PathVariable Long id, @RequestBody List<Map<String, Object>> lines) {
        jdbc.update("DELETE FROM rk3_object_lines WHERE rk3_object_id = :id", Map.of("id", id));
        int order = 10;
        for (var l : lines) {
            Map<String, Object> p = new HashMap<>();
            p.put("o", id);
            p.put("k", l.get("line_kind"));
            p.put("m", num(l.get("material_id")));
            p.put("ln", l.get("layer_name"));
            p.put("pq", dec(l.get("planned_qty")));
            p.put("aq", dec(l.get("accepted_qty")));
            p.put("u", l.getOrDefault("unit_code", "M2"));
            p.put("r", dec(l.get("rolls_qty")));
            p.put("pr", dec(l.get("price_per_roll")));
            p.put("amt", dec(l.get("amount")));
            p.put("ord", order);
            jdbc.update("""
                    INSERT INTO rk3_object_lines (rk3_object_id, line_kind, material_id, layer_name,
                        planned_qty, accepted_qty, unit_code, rolls_qty, price_per_roll, amount, sort_order)
                    VALUES (:o, :k, :m, :ln, :pq, :aq, :u, :r, :pr, :amt, :ord)
                    """, p);
            order += 10;
        }
        return object(id);
    }

    /**
     * Расчёт вырубки. Возвращает p, k, ставку и формулу либо понятный отказ,
     * если норматив не утверждён — молча подставлять число нельзя (P0, Q-03).
     */
    @PostMapping("/objects/{id}/calc-demolition")
    public Map<String, Object> calcDemolition(@PathVariable Long id) {
        var obj = jdbc.queryForMap("SELECT * FROM rk3_objects WHERE id = :id", Map.of("id", id));
        BigDecimal accepted = dec(obj.get("accepted_demolition_m2"));
        BigDecimal hours = dec(obj.get("fact_demolition_man_hours"));
        LocalDate date = obj.get("accepted_at") != null
                ? ((java.sql.Date) obj.get("accepted_at")).toLocalDate() : LocalDate.now();

        PayrollService.DemolitionCalc calc = payroll.calcDemolition(accepted, hours, date);

        Map<String, Object> p = new HashMap<>();
        p.put("id", id);
        p.put("prod", calc.productivity());
        p.put("k", calc.coefficient());
        p.put("rate", calc.baseRate());
        p.put("amount", calc.amount());
        p.put("f", calc.formula());
        jdbc.update("""
                UPDATE rk3_objects SET productivity = :prod, coefficient_k = :k,
                       base_rate_demolition = :rate, payroll_demolition = :amount, calc_formula = :f,
                       updated_at = NOW()
                WHERE id = :id
                """, p);

        Map<String, Object> result = new HashMap<>();
        result.put("productivity", calc.productivity());
        result.put("coefficient", calc.coefficient());
        result.put("base_rate", calc.baseRate());
        result.put("amount", calc.amount());
        result.put("formula", calc.formula());
        result.put("error", calc.error());
        audit.action("RK3_OBJECT", id, "CALC_DEMOLITION",
                calc.error() != null ? calc.error() : calc.formula());
        return result;
    }

    private static Long num(Object v) {
        return v == null ? null : ((Number) v).longValue();
    }

    private static BigDecimal dec(Object v) {
        if (v == null || String.valueOf(v).isBlank()) return null;
        return new BigDecimal(String.valueOf(v));
    }

    private static LocalDate date(Object v) {
        if (v == null || String.valueOf(v).isBlank()) return null;
        if (v instanceof java.sql.Date d) return d.toLocalDate();
        return LocalDate.parse(String.valueOf(v));
    }
}
