package ru.dipol.controller;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.web.bind.annotation.*;
import ru.dipol.audit.CurrentUser;
import ru.dipol.service.AuditService;
import ru.dipol.service.DocumentService;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Шаблоны документов, генерация актов и сообщений, конструктор отчётов. */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final NamedParameterJdbcTemplate jdbc;
    private final DocumentService service;
    private final AuditService audit;

    public DocumentController(NamedParameterJdbcTemplate jdbc, DocumentService service,
                              AuditService audit) {
        this.jdbc = jdbc;
        this.service = service;
        this.audit = audit;
    }

    // ---- шаблоны ------------------------------------------------------------

    @GetMapping("/templates")
    public List<Map<String, Object>> templates(@RequestParam(required = false) Long configId,
                                               @RequestParam(required = false) String docType) {
        StringBuilder sql = new StringBuilder("""
                SELECT t.*, c.name AS config_name FROM document_templates t
                LEFT JOIN project_configs c ON c.id = t.project_config_id WHERE 1=1
                """);
        Map<String, Object> p = new HashMap<>();
        if (configId != null) {
            sql.append(" AND (t.project_config_id = :c OR t.project_config_id IS NULL)");
            p.put("c", configId);
        }
        if (docType != null && !docType.isBlank()) {
            sql.append(" AND t.doc_type = :d");
            p.put("d", docType);
        }
        sql.append(" ORDER BY t.code, t.version DESC");
        return jdbc.queryForList(sql.toString(), p);
    }

    /**
     * Новая версия шаблона. Прежняя не редактируется: документы, сформированные
     * по ней, должны воспроизводиться как есть.
     */
    @PostMapping("/templates")
    public Map<String, Object> createTemplate(@RequestBody Map<String, Object> body) {
        String code = String.valueOf(body.get("code"));
        Integer nextVersion = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version),0)+1 FROM document_templates WHERE code = :c",
                Map.of("c", code), Integer.class);
        var kh = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO document_templates (code, name, doc_type, project_config_id, version, body,
                                                output_format, valid_from, author)
                VALUES (:c, :n, :d, :cfg, :v, :b, :f, :from, :a)
                """, new MapSqlParameterSource()
                        .addValue("c", code)
                        .addValue("n", body.get("name"))
                        .addValue("d", body.getOrDefault("doc_type", "ACT"))
                        .addValue("cfg", num(body.get("project_config_id")))
                        .addValue("v", nextVersion)
                        .addValue("b", body.get("body"))
                        .addValue("f", body.getOrDefault("output_format", "HTML"))
                        .addValue("from", body.get("valid_from") != null
                                ? LocalDate.parse(String.valueOf(body.get("valid_from"))) : LocalDate.now())
                        .addValue("a", CurrentUser.name()),
                kh, new String[]{"id"});
        Long id = ((Number) kh.getKeys().get("id")).longValue();
        audit.action("DOCUMENT_TEMPLATE", id, "CREATE", code + " v" + nextVersion);
        return Map.of("id", id, "version", nextVersion);
    }

    /** Переменные, доступные в шаблоне: подсказка при его редактировании. */
    @GetMapping("/templates/variables")
    public Map<String, Object> variables(@RequestParam(required = false) Long requestId) {
        if (requestId != null) {
            Map<String, Object> ctx = new HashMap<>(service.requestContext(requestId));
            ctx.remove("_lines");
            return ctx;
        }
        return Map.ofEntries(
                Map.entry("request.number", "Внутренний номер заявки"),
                Map.entry("request.external_number", "Номер заявки заказчика"),
                Map.entry("request.address", "Адрес дома"),
                Map.entry("request.apartment", "Квартира"),
                Map.entry("request.entrance", "Подъезд"),
                Map.entry("request.description", "Описание проблемы"),
                Map.entry("request.act_wording", "Формулировка работ для акта"),
                Map.entry("request.done_date", "Дата выполнения"),
                Map.entry("request.accepted_date", "Дата приёмки заказчиком"),
                Map.entry("client.name", "Клиент"),
                Map.entry("project.name", "Проект"),
                Map.entry("zheu.name", "ЖЭУ на дату документа"),
                Map.entry("zheu.head", "Начальник ЖЭУ"),
                Map.entry("responsible.name", "Ответственный"),
                Map.entry("document.date", "Дата документа"),
                Map.entry("works.list", "Список работ, по строке на каждую"),
                Map.entry("works.inline", "Список работ через точку с запятой")
        );
    }

    // ---- сформированные документы -------------------------------------------

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) Long requestId,
                                          @RequestParam(required = false) String docType,
                                          @RequestParam(defaultValue = "200") int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT d.id, d.doc_type, d.number, d.status, d.generated_at, d.generated_by,
                       d.template_version, d.request_id, t.name AS template_name
                FROM documents d LEFT JOIN document_templates t ON t.id = d.template_id WHERE 1=1
                """);
        Map<String, Object> p = new HashMap<>();
        if (requestId != null) {
            sql.append(" AND d.request_id = :r");
            p.put("r", requestId);
        }
        if (docType != null && !docType.isBlank()) {
            sql.append(" AND d.doc_type = :d");
            p.put("d", docType);
        }
        sql.append(" ORDER BY d.generated_at DESC LIMIT :lim");
        p.put("lim", limit);
        return jdbc.queryForList(sql.toString(), p);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable Long id) {
        return jdbc.queryForMap("SELECT * FROM documents WHERE id = :id", Map.of("id", id));
    }

    @PostMapping("/generate")
    public Map<String, Object> generate(@RequestBody Map<String, Object> body) {
        return service.generateForRequest(num(body.get("request_id")),
                String.valueOf(body.getOrDefault("template_code", "ACT")));
    }

    /** Предпросмотр без сохранения: текст сообщения MAX правят перед отправкой. */
    @PostMapping("/preview")
    public Map<String, Object> preview(@RequestBody Map<String, Object> body) {
        Map<String, Object> ctx = service.requestContext(num(body.get("request_id")));
        String rendered = service.render(String.valueOf(body.get("template")), ctx);
        return Map.of("body", rendered);
    }

    /** Отметка об отправке сообщения — событие заявки, а не просто галка. */
    @PostMapping("/{id}/sent")
    public Map<String, Object> markSent(@PathVariable Long id, @RequestBody Map<String, String> body) {
        jdbc.update("UPDATE documents SET status = 'SENT' WHERE id = :id", Map.of("id", id));
        var doc = jdbc.queryForMap("SELECT request_id FROM documents WHERE id = :id", Map.of("id", id));
        if (doc.get("request_id") != null) {
            Map<String, Object> p = new HashMap<>();
            p.put("r", doc.get("request_id"));
            p.put("t", "Сообщение отправлено" + (body.get("link") != null ? ": " + body.get("link") : ""));
            p.put("a", CurrentUser.name());
            jdbc.update("""
                    INSERT INTO request_events (request_id, event_type, text, actor)
                    VALUES (:r, 'MESSAGE', :t, :a)
                    """, p);
        }
        return Map.of("status", "SENT");
    }

    @PostMapping("/{id}/sign")
    public Map<String, Object> attachSigned(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        jdbc.update("""
                UPDATE documents SET signed_file_id = :f, status = 'SIGNED', signed_at = NOW()
                WHERE id = :id
                """, Map.of("f", num(body.get("file_id")), "id", id));
        audit.action("DOCUMENT", id, "SIGN", null);
        return Map.of("status", "SIGNED");
    }

    // ---- сохранённые отчёты -------------------------------------------------

    @GetMapping("/report-templates")
    public List<Map<String, Object>> reportTemplates() {
        return jdbc.queryForList("SELECT * FROM report_templates ORDER BY name", Map.of());
    }

    @PostMapping("/report-templates")
    public Map<String, Object> createReportTemplate(@RequestBody Map<String, Object> body) {
        Long id = jdbc.queryForObject("""
                INSERT INTO report_templates (name, entity, filters_json, columns_json, is_external, is_shared)
                VALUES (:n, :e, :f, :c, :ext, :sh) RETURNING id
                """, new MapSqlParameterSource()
                        .addValue("n", body.get("name"))
                        .addValue("e", body.get("entity"))
                        .addValue("f", body.get("filters_json"))
                        .addValue("c", body.get("columns_json"))
                        .addValue("ext", Boolean.TRUE.equals(body.get("is_external")))
                        .addValue("sh", Boolean.TRUE.equals(body.get("is_shared"))), Long.class);
        return Map.of("id", id);
    }

    private static Long num(Object v) {
        return v == null ? null : ((Number) v).longValue();
    }
}
