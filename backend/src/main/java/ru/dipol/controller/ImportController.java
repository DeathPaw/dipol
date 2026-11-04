package ru.dipol.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import ru.dipol.audit.CurrentUser;
import ru.dipol.exception.BusinessRuleException;
import ru.dipol.importer.ExcelReader;
import ru.dipol.model.Request;
import ru.dipol.service.AuditService;
import ru.dipol.service.BuildingService;
import ru.dipol.service.PermissionService;
import ru.dipol.service.RequestService;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Импорт заявок из выгрузки заказчика (US-B1, US-N3).
 *
 * <p>Порядок ровно как в ТЗ: загрузка файла → сопоставление колонок с полями
 * ERP → сопоставление адресов → предварительный просмотр новых, совпавших и
 * спорных строк → применение. Повторная загрузка того же внешнего номера
 * обновляет заявку, а не создаёт дубль.
 */
@RestController
@RequestMapping("/api/import")
public class ImportController {

    private final ExcelReader excel;
    private final BuildingService buildings;
    private final RequestService requests;
    private final NamedParameterJdbcTemplate jdbc;
    private final PermissionService permissions;
    private final AuditService audit;
    private final ObjectMapper json = new ObjectMapper();

    public ImportController(ExcelReader excel, BuildingService buildings, RequestService requests,
                            NamedParameterJdbcTemplate jdbc, PermissionService permissions,
                            AuditService audit) {
        this.excel = excel;
        this.buildings = buildings;
        this.requests = requests;
        this.jdbc = jdbc;
        this.permissions = permissions;
        this.audit = audit;
    }

    /** Шаг 1: что за файл — какие листы и колонки в нём есть. */
    @PostMapping("/inspect")
    public Map<String, Object> inspect(@RequestParam("file") MultipartFile file,
                                       @RequestParam(required = false) String sheet) {
        permissions.require("IMPORT_RUN", "Импорт требует права «Импорт и миграция»");
        try (var in = file.getInputStream()) {
            List<String> sheets = excel.sheetNames(in);
            try (var in2 = file.getInputStream()) {
                var read = excel.read(in2, sheet != null ? sheet : sheets.get(0), null, 20);
                return Map.of("sheets", sheets, "sheet", read.name(),
                        "headers", read.headers(), "sample", read.rows());
            }
        } catch (IOException e) {
            throw new BusinessRuleException("Не удалось прочитать файл: " + e.getMessage());
        }
    }

    /**
     * Шаг 2: предварительный просмотр. Ничего не создаётся — строки
     * раскладываются на новые, совпавшие и спорные по адресу.
     */
    @PostMapping("/preview")
    public Map<String, Object> preview(@RequestParam("file") MultipartFile file,
                                       @RequestParam String sheet,
                                       @RequestParam String mapping,
                                       @RequestParam(defaultValue = "SUPER_MKD") String source,
                                       @RequestParam(defaultValue = "1000") int maxRows) {
        permissions.require("IMPORT_RUN", null);
        Map<String, String> map = parseMapping(mapping);

        var kh = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO import_batches (filename, source, mapping_json, created_by)
                VALUES (:f, :s, :m, :a)
                """, new MapSqlParameterSource()
                        .addValue("f", file.getOriginalFilename())
                        .addValue("s", source)
                        .addValue("m", mapping)
                        .addValue("a", CurrentUser.name()),
                kh, new String[]{"id"});
        Long batchId = ((Number) kh.getKeys().get("id")).longValue();

        int neu = 0, matched = 0, ambiguous = 0, errors = 0;
        try (var in = file.getInputStream()) {
            var read = excel.read(in, sheet, null, maxRows);
            for (var row : read.rows()) {
                String address = str(row.get(map.get("address")));
                String externalNumber = str(row.get(map.get("external_number")));

                String status;
                Long buildingId = null;
                String candidates = null;
                String error = null;

                if (address == null || address.isBlank()) {
                    status = "ERROR";
                    error = "Пустой адрес";
                    errors++;
                } else {
                    var match = buildings.match(address, null, str(row.get(map.get("fias"))), source);
                    switch (match.status()) {
                        case "MATCHED" -> {
                            buildingId = match.building().id();
                            status = "MATCHED";
                            matched++;
                        }
                        case "AMBIGUOUS" -> {
                            status = "AMBIGUOUS";
                            candidates = toJson(match.candidates().stream()
                                    .map(b -> Map.of("id", b.id(), "address", b.address())).toList());
                            ambiguous++;
                        }
                        default -> {
                            status = "ERROR";
                            error = "Адрес не сопоставлен с домом";
                            errors++;
                        }
                    }
                }

                Long existingId = null;
                if (externalNumber != null && !externalNumber.isBlank()) {
                    existingId = jdbc.queryForList(
                            "SELECT id FROM requests WHERE external_system = :s AND external_number = :n",
                            Map.of("s", source, "n", externalNumber), Long.class)
                            .stream().findFirst().orElse(null);
                }
                if (existingId == null && "MATCHED".equals(status)) neu++;

                Map<String, Object> p = new HashMap<>();
                p.put("b", batchId);
                p.put("r", row.get("_row"));
                p.put("raw", toJson(row));
                p.put("st", status);
                p.put("bld", buildingId);
                p.put("req", existingId);
                p.put("c", candidates);
                p.put("e", error);
                jdbc.update("""
                        INSERT INTO import_rows (batch_id, row_no, raw_json, match_status, building_id,
                                                 request_id, candidates_json, error)
                        VALUES (:b, :r, :raw, :st, :bld, :req, :c, :e)
                        """, p);
            }
        } catch (IOException e) {
            throw new BusinessRuleException("Не удалось прочитать файл: " + e.getMessage());
        }

        jdbc.update("""
                UPDATE import_batches SET total_rows = :t, new_rows = :n, matched_rows = :m,
                       conflict_rows = :c WHERE id = :id
                """, Map.of("t", neu + matched + ambiguous + errors, "n", neu, "m", matched,
                            "c", ambiguous + errors, "id", batchId));

        Map<String, Object> result = new HashMap<>();
        result.put("batch_id", batchId);
        result.put("new", neu);
        result.put("matched", matched);
        result.put("ambiguous", ambiguous);
        result.put("errors", errors);
        result.put("rows", rows(batchId, null));
        return result;
    }

    @GetMapping("/batches")
    public List<Map<String, Object>> batches() {
        permissions.require("IMPORT_RUN", null);
        return jdbc.queryForList("SELECT * FROM import_batches ORDER BY created_at DESC LIMIT 50", Map.of());
    }

    @GetMapping("/batches/{id}/rows")
    public List<Map<String, Object>> rows(@PathVariable Long id,
                                          @RequestParam(required = false) String status) {
        Map<String, Object> p = new HashMap<>();
        p.put("b", id);
        String filter = "";
        if (status != null && !status.isBlank()) {
            filter = " AND match_status = :s";
            p.put("s", status);
        }
        return jdbc.queryForList(
                "SELECT * FROM import_rows WHERE batch_id = :b" + filter + " ORDER BY row_no LIMIT 2000", p);
    }

    /** Ручное решение оператора по спорной строке: выбранный дом запоминается. */
    @PostMapping("/rows/{rowId}/resolve")
    public Map<String, Object> resolve(@PathVariable Long rowId, @RequestBody Map<String, Object> body) {
        permissions.require("IMPORT_RUN", null);
        Long buildingId = ((Number) body.get("building_id")).longValue();
        var row = jdbc.queryForMap("SELECT * FROM import_rows WHERE id = :id", Map.of("id", rowId));
        var batch = jdbc.queryForMap("SELECT * FROM import_batches WHERE id = :id",
                Map.of("id", row.get("batch_id")));
        Map<String, String> map = parseMapping(String.valueOf(batch.get("mapping_json")));
        Map<?, ?> raw = fromJson(String.valueOf(row.get("raw_json")));
        String address = str(raw.get(map.get("address")));

        // После ручного решения вариант написания запоминается за домом —
        // следующая загрузка сопоставит его сама (US-A6).
        buildings.rememberAlias(buildingId, address, String.valueOf(batch.get("source")));

        jdbc.update("""
                UPDATE import_rows SET building_id = :b, match_status = 'MATCHED',
                       resolved_by = :a, resolved_at = NOW(), error = NULL
                WHERE id = :id
                """, Map.of("b", buildingId, "a", CurrentUser.name(), "id", rowId));
        return Map.of("status", "resolved");
    }

    /** Шаг 3: применение. Создаются только сопоставленные строки. */
    @PostMapping("/batches/{id}/apply")
    public Map<String, Object> apply(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        permissions.require("IMPORT_RUN", null);
        Long clientId = ((Number) body.get("client_id")).longValue();
        Long projectId = ((Number) body.get("project_id")).longValue();
        var batch = jdbc.queryForMap("SELECT * FROM import_batches WHERE id = :id", Map.of("id", id));
        String source = String.valueOf(batch.get("source"));
        Map<String, String> map = parseMapping(String.valueOf(batch.get("mapping_json")));

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM import_rows WHERE batch_id = :b AND match_status = 'MATCHED'",
                Map.of("b", id));

        int created = 0, updated = 0, failed = 0;
        List<String> failures = new ArrayList<>();
        for (var row : rows) {
            Map<?, ?> raw = fromJson(String.valueOf(row.get("raw_json")));
            Long existingId = row.get("request_id") != null
                    ? ((Number) row.get("request_id")).longValue() : null;
            try {
                if (existingId != null) {
                    // Повторный импорт не перезаписывает внутренний факт
                    // выполнения — обновляем только внешние поля (US-N3).
                    Map<String, Object> p = new HashMap<>();
                    p.put("id", existingId);
                    p.put("d", str(raw.get(map.get("description"))));
                    p.put("a", str(raw.get(map.get("apartment"))));
                    jdbc.update("""
                            UPDATE requests SET description = COALESCE(:d, description),
                                   apartment = COALESCE(:a, apartment), updated_at = NOW()
                            WHERE id = :id
                            """, p);
                    updated++;
                    continue;
                }
                Request draft = new Request(null, null, null,
                        str(raw.get(map.get("external_number"))), source,
                        "CUSTOMER_SYSTEM", clientId, null, projectId, null, null, null,
                        ((Number) row.get("building_id")).longValue(), null, null, null, null,
                        str(raw.get(map.get("apartment"))), null, false, null, null, null,
                        dateTime(raw.get(map.get("received_at"))), LocalDateTime.now(),
                        null, null, null,
                        emergency(raw.get(map.get("urgency"))) ? "EMERGENCY" : "PLANNED",
                        false, null, null, null,
                        "CREATED", null, null, false, false, null, null,
                        0, null, str(raw.get(map.get("description"))), null, null,
                        null, null, null, str(raw.get(map.get("contact_name"))),
                        str(raw.get(map.get("contact_phone"))),
                        null, null, null, CurrentUser.name(), null);
                var createdRequest = requests.create(draft);
                jdbc.update("UPDATE import_rows SET request_id = :r WHERE id = :id",
                        Map.of("r", createdRequest.id(), "id", row.get("id")));
                created++;
            } catch (Exception e) {
                failed++;
                failures.add("строка " + row.get("row_no") + ": " + e.getMessage());
                jdbc.update("UPDATE import_rows SET match_status = 'ERROR', error = :e WHERE id = :id",
                        Map.of("e", e.getMessage(), "id", row.get("id")));
            }
        }

        jdbc.update("UPDATE import_batches SET status = 'APPLIED', applied_at = NOW() WHERE id = :id",
                Map.of("id", id));
        audit.action("IMPORT_BATCH", id, "APPLY",
                "создано " + created + ", обновлено " + updated + ", ошибок " + failed);

        Map<String, Object> result = new HashMap<>();
        result.put("created", created);
        result.put("updated", updated);
        result.put("failed", failed);
        result.put("failures", failures);
        return result;
    }

    /** Протокол контрольной сверки миграции (US-N5). */
    @GetMapping("/reconciliation")
    public List<Map<String, Object>> reconciliation() {
        return jdbc.queryForList(
                "SELECT * FROM import_reconciliation ORDER BY source_book, source_sheet, period", Map.of());
    }

    @GetMapping("/legacy-map")
    public List<Map<String, Object>> legacyMap(@RequestParam(required = false) String entityType,
                                               @RequestParam(defaultValue = "200") int limit) {
        Map<String, Object> p = new HashMap<>();
        p.put("lim", limit);
        String filter = "";
        if (entityType != null && !entityType.isBlank()) {
            filter = " WHERE entity_type = :t";
            p.put("t", entityType);
        }
        return jdbc.queryForList(
                "SELECT * FROM legacy_map" + filter + " ORDER BY imported_at DESC LIMIT :lim", p);
    }

    private Map<String, String> parseMapping(String mapping) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> m = json.readValue(mapping, Map.class);
            if (!m.containsKey("address")) {
                throw new BusinessRuleException("В сопоставлении колонок обязательно поле «address»");
            }
            return m;
        } catch (BusinessRuleException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessRuleException("Некорректное сопоставление колонок: " + e.getMessage());
        }
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Map<?, ?> fromJson(String value) {
        try {
            return json.readValue(value, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String str(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static LocalDateTime dateTime(Object v) {
        String s = str(v);
        if (s == null) return null;
        try {
            return s.length() <= 10 ? LocalDate.parse(s).atStartOfDay() : LocalDateTime.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean emergency(Object v) {
        String s = str(v);
        if (s == null) return false;
        return s.equalsIgnoreCase("да") || s.equals("1") || s.equalsIgnoreCase("true")
                || s.toLowerCase().contains("аварий");
    }
}
