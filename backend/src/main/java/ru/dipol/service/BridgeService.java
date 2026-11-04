package ru.dipol.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.dipol.audit.CurrentUser;
import ru.dipol.repository.ParameterRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Отчёт «Мост»: раскладка начисленного ФОТ на официальную и кэш-часть с
 * разнесением по проектам за произвольный период.
 *
 * <p><b>Статус формулы.</b> Точное бизнес-правило распределения не утверждено
 * (вопрос Q-11). Здесь воспроизведён алгоритм действующего листа
 * ФОТ_Нормализация: начисления сотрудника внутри месяца идут по возрастанию
 * даты, пока накопленная сумма не превысит лимит официальной части; всё сверх
 * лимита уходит в кэш. Лимит — версионируемый параметр, а не константа кода.
 *
 * <p>Результат можно раскрыть до конкретных начислений: каждая строка отчёта
 * ссылается на сотрудника, проект и период, а детализация берётся из
 * payroll_entries тем же фильтром.
 */
@Service
public class BridgeService {

    private final NamedParameterJdbcTemplate jdbc;
    private final ParameterRepository parameters;
    private final ObjectMapper json = new ObjectMapper();

    public BridgeService(NamedParameterJdbcTemplate jdbc, ParameterRepository parameters) {
        this.jdbc = jdbc;
        this.parameters = parameters;
    }

    public Map<String, Object> build(LocalDate from, LocalDate to, boolean includeMaterials) {
        BigDecimal defaultLimit = parameters.numberOn("WHITE_SALARY_LIMIT", to);

        // Индивидуальные лимиты, если заданы правилом «Моста».
        Map<Long, BigDecimal> limits = employeeLimits(to);

        List<Map<String, Object>> entries = jdbc.queryForList("""
                SELECT pe.id, pe.employee_id, e.full_name, pe.project_id, p.code AS project_code,
                       p.name AS project_name, pe.period_month, pe.entry_date, pe.amount
                FROM payroll_entries pe
                JOIN employees e ON e.id = pe.employee_id
                JOIN projects p ON p.id = pe.project_id
                WHERE pe.status <> 'REVERSED' AND pe.entry_date BETWEEN :f AND :t
                ORDER BY pe.employee_id, pe.period_month, pe.entry_date, pe.id
                """, Map.of("f", from, "t", to));

        // Накопитель по сотруднику и месяцу: лимит расходуется внутри месяца,
        // а не за весь период отчёта — иначе июньские и июльские начисления
        // делили бы один лимит.
        Map<String, BigDecimal> consumed = new HashMap<>();
        Map<String, Map<String, Object>> byEmployeeProject = new LinkedHashMap<>();

        for (var e : entries) {
            Long employeeId = ((Number) e.get("employee_id")).longValue();
            String monthKey = employeeId + "|" + e.get("period_month");
            BigDecimal limit = limits.getOrDefault(employeeId, defaultLimit);
            if (limit == null) limit = BigDecimal.ZERO;

            BigDecimal amount = new BigDecimal(String.valueOf(e.get("amount")));
            BigDecimal used = consumed.getOrDefault(monthKey, BigDecimal.ZERO);
            BigDecimal room = limit.subtract(used).max(BigDecimal.ZERO);
            BigDecimal official = amount.min(room).max(BigDecimal.ZERO);
            BigDecimal cash = amount.subtract(official);
            consumed.put(monthKey, used.add(official));

            String key = employeeId + "|" + e.get("project_id");
            var row = byEmployeeProject.computeIfAbsent(key, k -> {
                Map<String, Object> r = new HashMap<>();
                r.put("employee_id", employeeId);
                r.put("employee_name", e.get("full_name"));
                r.put("project_id", e.get("project_id"));
                r.put("project_code", e.get("project_code"));
                r.put("project_name", e.get("project_name"));
                r.put("accrued", BigDecimal.ZERO);
                r.put("official", BigDecimal.ZERO);
                r.put("cash", BigDecimal.ZERO);
                return r;
            });
            row.put("accrued", ((BigDecimal) row.get("accrued")).add(amount));
            row.put("official", ((BigDecimal) row.get("official")).add(official));
            row.put("cash", ((BigDecimal) row.get("cash")).add(cash));
        }

        // Свод по проектам плюс материалы, если их просят показать рядом:
        // именно так устроен действующий лист МОСТ.
        Map<String, Map<String, Object>> byProject = new LinkedHashMap<>();
        for (var row : byEmployeeProject.values()) {
            String key = String.valueOf(row.get("project_id"));
            var p = byProject.computeIfAbsent(key, k -> {
                Map<String, Object> r = new HashMap<>();
                r.put("project_id", row.get("project_id"));
                r.put("project_code", row.get("project_code"));
                r.put("project_name", row.get("project_name"));
                r.put("accrued", BigDecimal.ZERO);
                r.put("official", BigDecimal.ZERO);
                r.put("cash", BigDecimal.ZERO);
                r.put("materials", BigDecimal.ZERO);
                return r;
            });
            p.put("accrued", ((BigDecimal) p.get("accrued")).add((BigDecimal) row.get("accrued")));
            p.put("official", ((BigDecimal) p.get("official")).add((BigDecimal) row.get("official")));
            p.put("cash", ((BigDecimal) p.get("cash")).add((BigDecimal) row.get("cash")));
        }

        if (includeMaterials) {
            List<Map<String, Object>> materials = jdbc.queryForList("""
                    SELECT sm.project_id, COALESCE(SUM(sm.amount), 0) AS total
                    FROM stock_movements sm
                    WHERE sm.movement_type = 'ISSUE' AND sm.project_id IS NOT NULL
                      AND sm.moved_at::date BETWEEN :f AND :t
                    GROUP BY sm.project_id
                    """, Map.of("f", from, "t", to));
            for (var m : materials) {
                String key = String.valueOf(m.get("project_id"));
                var p = byProject.get(key);
                if (p != null) {
                    p.put("materials", new BigDecimal(String.valueOf(m.get("total"))));
                }
            }
        }

        for (var p : byProject.values()) {
            BigDecimal total = ((BigDecimal) p.get("official"))
                    .add((BigDecimal) p.get("cash"))
                    .add((BigDecimal) p.getOrDefault("materials", BigDecimal.ZERO));
            p.put("total", total);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("period_from", from);
        result.put("period_to", to);
        result.put("default_white_limit", defaultLimit);
        result.put("by_employee_project", new ArrayList<>(byEmployeeProject.values()));
        result.put("by_project", new ArrayList<>(byProject.values()));
        result.put("rule_note", "Алгоритм воспроизводит лист ФОТ_Нормализация и ожидает "
                + "утверждения бизнес-правила (вопрос Q-11)");
        return result;
    }

    /** Сохранение снимка отчёта: итог должен оставаться воспроизводимым позже. */
    @Transactional
    public Long save(LocalDate from, LocalDate to, Map<String, Object> data) {
        Long reportId;
        try {
            reportId = jdbc.queryForObject("""
                    INSERT INTO bridge_reports (period_from, period_to, data_json, created_by)
                    VALUES (:f, :t, :d, :a) RETURNING id
                    """, Map.of("f", from, "t", to,
                                "d", json.writeValueAsString(data), "a", CurrentUser.name()), Long.class);
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось сохранить отчёт «Мост»: " + e.getMessage(), e);
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byProject = (List<Map<String, Object>>) data.get("by_project");
        for (var p : byProject) {
            Map<String, Object> row = new HashMap<>();
            row.put("r", reportId);
            row.put("p", p.get("project_id"));
            row.put("acc", p.get("accrued"));
            row.put("off", p.get("official"));
            row.put("cash", p.get("cash"));
            row.put("mat", p.getOrDefault("materials", BigDecimal.ZERO));
            row.put("tot", p.get("total"));
            jdbc.update("""
                    INSERT INTO bridge_lines (report_id, project_id, accrued, official, cash, materials, total)
                    VALUES (:r, :p, :acc, :off, :cash, :mat, :tot)
                    """, row);
        }
        return reportId;
    }

    private Map<Long, BigDecimal> employeeLimits(LocalDate on) {
        Map<Long, BigDecimal> result = new HashMap<>();
        List<Map<String, Object>> rules = jdbc.queryForList("""
                SELECT params_json FROM bridge_rules
                WHERE valid_from <= :d AND (valid_to IS NULL OR valid_to >= :d)
                ORDER BY valid_from DESC LIMIT 1
                """, Map.of("d", on));
        if (rules.isEmpty()) return result;
        try {
            Map<?, ?> parsed = json.readValue(String.valueOf(rules.get(0).get("params_json")), Map.class);
            Object limits = parsed.get("employee_limits");
            if (limits instanceof Map<?, ?> m) {
                m.forEach((k, v) -> result.put(Long.valueOf(String.valueOf(k)),
                        new BigDecimal(String.valueOf(v))));
            }
        } catch (Exception ignored) {
            // Повреждённое правило не должно ронять отчёт: используем общий лимит.
        }
        return result;
    }
}
