package ru.dipol.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.dipol.audit.CurrentUser;
import ru.dipol.exception.BusinessRuleException;
import ru.dipol.exception.EntityNotFoundException;
import ru.dipol.model.Request;
import ru.dipol.model.RequestItem;
import ru.dipol.repository.RequestRepository;
import ru.dipol.repository.ZheuRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Формирование документов из принятого факта.
 *
 * <p>Шаблон оперирует именованными переменными ({@code {{request.number}}}),
 * а не адресами ячеек — изменение формы не требует правки кода (US-K1, US-N4).
 * Сформированный документ хранит использованную версию шаблона и снимок
 * данных, поэтому старый акт воспроизводится и после смены шаблона.
 *
 * <p>Числовой объём в самом документе не редактируется: исправление идёт
 * через факт или корректировку (US-K2).
 */
@Service
public class DocumentService {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_.]+)\\s*}}");
    private static final DateTimeFormatter RU_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final NamedParameterJdbcTemplate jdbc;
    private final RequestRepository requests;
    private final ZheuRepository zheu;
    private final AuditService audit;
    private final ObjectMapper json = new ObjectMapper();

    public DocumentService(NamedParameterJdbcTemplate jdbc, RequestRepository requests,
                           ZheuRepository zheu, AuditService audit) {
        this.jdbc = jdbc;
        this.requests = requests;
        this.zheu = zheu;
        this.audit = audit;
    }

    /** Данные заявки для шаблона: плоский набор именованных переменных. */
    public Map<String, Object> requestContext(Long requestId) {
        Request r = requests.findById(requestId)
                .orElseThrow(() -> new EntityNotFoundException("Заявка", requestId));
        List<RequestItem> items = requests.items(requestId);

        // В акт и сообщение попадают только фактически выполненные ненулевые
        // позиции — нулевые строки в документе лишние (US-K3).
        List<Map<String, Object>> lines = new ArrayList<>();
        for (RequestItem i : items) {
            BigDecimal qty = i.acceptedQty() != null ? i.acceptedQty() : i.factQty();
            if (qty == null || qty.compareTo(BigDecimal.ZERO) == 0) continue;
            Map<String, Object> line = new HashMap<>();
            line.put("sku_name", i.skuName());
            line.put("qty", qty.stripTrailingZeros().toPlainString());
            line.put("unit", unitName(i.unitCode()));
            line.put("text", i.skuName() + " — " + qty.stripTrailingZeros().toPlainString()
                    + " " + unitName(i.unitCode()));
            lines.add(line);
        }

        LocalDate docDate = r.customerAcceptedAt() != null ? r.customerAcceptedAt()
                : (r.doneAt() != null ? r.doneAt().toLocalDate() : LocalDate.now());
        // ЖЭУ берём на дату документа, а не текущее: связь дома с ЖЭУ меняется.
        var zheuOnDate = zheu.forBuildingOn(r.buildingId(), docDate)
                .or(() -> r.zheuId() != null ? zheu.findById(r.zheuId()) : java.util.Optional.empty());

        Map<String, Object> ctx = new HashMap<>();
        ctx.put("request.number", r.number());
        ctx.put("request.external_number", nz(r.externalNumber()));
        ctx.put("request.address", r.buildingAddress());
        ctx.put("request.apartment", nz(r.apartment()));
        ctx.put("request.entrance", r.entrance() != null ? String.valueOf(r.entrance()) : "");
        ctx.put("request.description", nz(r.description()));
        ctx.put("request.act_wording", nz(r.actWording()));
        ctx.put("request.done_date", r.doneAt() != null ? r.doneAt().toLocalDate().format(RU_DATE) : "");
        ctx.put("request.accepted_date", r.customerAcceptedAt() != null
                ? r.customerAcceptedAt().format(RU_DATE) : "");
        ctx.put("request.received_date", r.receivedAt().toLocalDate().format(RU_DATE));
        ctx.put("client.name", r.clientName());
        ctx.put("project.name", r.projectName());
        ctx.put("project.code", r.projectCode());
        ctx.put("zheu.name", zheuOnDate.map(z -> nz(z.name())).orElse(""));
        ctx.put("zheu.head", zheuOnDate.map(z -> nz(z.headName())).orElse(""));
        ctx.put("responsible.name", nz(r.responsibleName()));
        ctx.put("document.date", docDate.format(RU_DATE));
        ctx.put("works.list", lines.stream().map(l -> String.valueOf(l.get("text")))
                .reduce((a, b) -> a + "\n" + b).orElse(""));
        ctx.put("works.inline", lines.stream().map(l -> String.valueOf(l.get("text")))
                .reduce((a, b) -> a + "; " + b).orElse(""));
        ctx.put("works.count", String.valueOf(lines.size()));
        ctx.put("_lines", lines);
        return ctx;
    }

    /** Подстановка значений в шаблон. Неизвестная переменная остаётся видимой. */
    public String render(String template, Map<String, Object> context) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            Object value = context.get(m.group(1));
            String replacement = value != null ? String.valueOf(value) : "{{?" + m.group(1) + "}}";
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    @Transactional
    public Map<String, Object> generateForRequest(Long requestId, String templateCode) {
        Request r = requests.findById(requestId)
                .orElseThrow(() -> new EntityNotFoundException("Заявка", requestId));
        var template = findTemplate(templateCode, r.configId());
        Map<String, Object> ctx = requestContext(requestId);
        String body = render(String.valueOf(template.get("body")), ctx);

        String dataJson;
        try {
            Map<String, Object> snapshot = new HashMap<>(ctx);
            dataJson = json.writeValueAsString(snapshot);
        } catch (Exception e) {
            dataJson = "{}";
        }

        var kh = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO documents (template_id, template_version, doc_type, number, entity_type,
                    entity_id, request_id, project_id, data_json, body, generated_by)
                VALUES (:t, :v, :dt, :num, 'REQUEST', :rid, :rid, :pid, :data, :body, :author)
                """, new MapSqlParameterSource()
                        .addValue("t", template.get("id"))
                        .addValue("v", template.get("version"))
                        .addValue("dt", template.get("doc_type"))
                        .addValue("num", r.number())
                        .addValue("rid", requestId)
                        .addValue("pid", r.projectId())
                        .addValue("data", dataJson)
                        .addValue("body", body)
                        .addValue("author", CurrentUser.name()),
                kh, new String[]{"id"});
        Long id = ((Number) kh.getKeys().get("id")).longValue();

        requests.addEvent(requestId, "DOC",
                "Сформирован документ: " + template.get("name"), null, null, CurrentUser.name());
        audit.action("DOCUMENT", id, "GENERATE", String.valueOf(template.get("code")));

        Map<String, Object> result = new HashMap<>();
        result.put("id", id);
        result.put("doc_type", template.get("doc_type"));
        result.put("body", body);
        result.put("template_version", template.get("version"));
        return result;
    }

    private Map<String, Object> findTemplate(String code, Long configId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT * FROM document_templates
                WHERE code = :c AND is_active
                  AND (project_config_id IS NULL OR project_config_id = :cfg)
                  AND valid_from <= CURRENT_DATE AND (valid_to IS NULL OR valid_to >= CURRENT_DATE)
                ORDER BY (project_config_id IS NOT NULL) DESC, version DESC
                LIMIT 1
                """, Map.of("c", code, "cfg", configId));
        if (rows.isEmpty()) {
            throw new BusinessRuleException("Шаблон «" + code + "» не найден или не действует на сегодня");
        }
        return rows.get(0);
    }

    private static String unitName(String code) {
        return switch (code) {
            case "M2" -> "м²";
            case "M3" -> "м³";
            case "MP" -> "м.пог.";
            case "PCS" -> "шт.";
            case "MH" -> "чел·ч";
            case "HOUR" -> "ч";
            default -> code;
        };
    }

    private static String nz(String v) {
        return v != null ? v : "";
    }
}
