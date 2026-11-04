package ru.dipol.repository;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import ru.dipol.model.*;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class RequestRepository {

    private static final String SELECT = """
            SELECT r.*, cl.name AS client_name, p.code AS project_code, p.name AS project_name,
                   p.config_id AS config_id, b.address AS building_address, b.legacy_id AS building_legacy_id,
                   z.name AS zheu_name, e.full_name AS responsible_name, st.name AS status_name
            FROM requests r
            JOIN clients cl ON cl.id = r.client_id
            JOIN projects p ON p.id = r.project_id
            JOIN buildings b ON b.id = r.building_id
            LEFT JOIN zheu z ON z.id = r.zheu_id
            LEFT JOIN employees e ON e.id = r.responsible_employee_id
            LEFT JOIN request_statuses st ON st.code = r.status
            """;

    private static final RowMapper<Request> MAPPER = RequestRepository::mapRequest;

    private static Request mapRequest(ResultSet rs, int rowNum) throws SQLException {
        return new Request(
                rs.getLong("id"), rs.getString("number"), rs.getObject("seq_in_project", Integer.class),
                rs.getString("external_number"), rs.getString("external_system"), rs.getString("source_code"),
                rs.getLong("client_id"), rs.getString("client_name"),
                rs.getLong("project_id"), rs.getString("project_code"), rs.getString("project_name"),
                rs.getLong("config_id"),
                rs.getLong("building_id"), rs.getString("building_address"),
                rs.getObject("building_legacy_id", Integer.class),
                rs.getObject("zheu_id", Long.class), rs.getString("zheu_name"),
                rs.getString("apartment"), rs.getObject("entrance", Integer.class),
                rs.getBoolean("entrance_is_manual"), rs.getObject("entrance_auto", Integer.class),
                rs.getString("entrance_fix_reason"), rs.getString("place_note"),
                ts(rs.getTimestamp("received_at_customer")), ts(rs.getTimestamp("received_at")),
                date(rs.getDate("planned_date")), ts(rs.getTimestamp("done_at")),
                date(rs.getDate("customer_accepted_at")),
                rs.getString("urgency"), rs.getBoolean("is_warranty"),
                rs.getObject("parent_request_id", Long.class), rs.getString("link_type"),
                rs.getString("link_reason"),
                rs.getString("status"), rs.getString("status_name"), rs.getString("financial_class"),
                rs.getBoolean("customer_closed"), rs.getBoolean("is_closed"),
                ts(rs.getTimestamp("closed_at")), rs.getString("cancel_reason"),
                rs.getInt("priority"), rs.getString("priority_reason"),
                rs.getString("description"), rs.getString("act_wording"), rs.getString("internal_comment"),
                rs.getObject("responsible_employee_id", Long.class), rs.getString("responsible_name"),
                rs.getObject("contact_id", Long.class), rs.getString("contact_adhoc_name"),
                rs.getString("contact_adhoc_phone"),
                rs.getString("report_link"), rs.getString("photo_link"), rs.getString("report_text"),
                rs.getString("created_by"), ts(rs.getTimestamp("created_at")));
    }

    private static final RowMapper<RequestItem> ITEM_MAPPER = (rs, n) -> {
        Long[] assignees = (Long[]) rs.getArray("assignee_ids").getArray();
        return new RequestItem(
                rs.getLong("id"), rs.getLong("request_id"), rs.getObject("work_object_id", Long.class),
                rs.getString("work_object_name"),
                rs.getLong("sku_id"), rs.getString("sku_code"), rs.getString("sku_name"),
                rs.getString("unit_code"),
                rs.getBigDecimal("planned_qty"), rs.getBigDecimal("declared_qty"),
                rs.getBigDecimal("fact_qty"), rs.getBigDecimal("remeasured_qty"),
                rs.getBigDecimal("accepted_qty"), rs.getBigDecimal("man_hours"),
                rs.getBigDecimal("reference_qty"), rs.getString("reference_unit_code"),
                rs.getString("repair_type"), rs.getString("status"),
                rs.getString("accepted_by"), ts(rs.getTimestamp("accepted_at")),
                rs.getString("accept_comment"), rs.getString("note"), rs.getInt("sort_order"),
                List.of(assignees));
    };

    private static final RowMapper<RequestEvent> EVENT_MAPPER = (rs, n) -> new RequestEvent(
            rs.getLong("id"), rs.getLong("request_id"), rs.getString("event_type"),
            rs.getString("text"), rs.getString("wait_reason"), rs.getString("payload_json"),
            rs.getString("actor"), ts(rs.getTimestamp("created_at")));

    private static final RowMapper<RequestStatusChange> STATUS_MAPPER = (rs, n) -> new RequestStatusChange(
            rs.getLong("id"), rs.getLong("request_id"), rs.getString("from_status"),
            rs.getString("to_status"), rs.getString("reason"), rs.getBoolean("is_override"),
            rs.getString("actor"), ts(rs.getTimestamp("created_at")));

    private static final RowMapper<ChecklistItem> CHECKLIST_MAPPER = (rs, n) -> new ChecklistItem(
            rs.getLong("id"), rs.getLong("request_id"), rs.getString("item_code"), rs.getString("name"),
            rs.getBoolean("is_required"), rs.getBoolean("is_done"), ts(rs.getTimestamp("done_at")),
            rs.getString("done_by"), rs.getString("skipped_reason"));

    private static LocalDateTime ts(java.sql.Timestamp t) {
        return t != null ? t.toLocalDateTime() : null;
    }

    private static LocalDate date(java.sql.Date d) {
        return d != null ? d.toLocalDate() : null;
    }

    private final NamedParameterJdbcTemplate jdbc;

    public RequestRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Фильтр списка заявок. Любой из признаков — независимо от остальных (US-B3). */
    public record Filter(
            String query, List<Long> projectIds, List<String> statuses, List<String> financialClasses,
            Long buildingId, Long zheuId, Long clientId, Long responsibleId, String urgency,
            Boolean isWarranty, Boolean customerClosed, Boolean isClosed,
            LocalDate dateFrom, LocalDate dateTo, String dateField,
            /** Просрочена: плановая дата прошла, а работы не завершены. */
            Boolean overdue,
            /** Без плана выполнения — то, что показывает дашборд. */
            Boolean unplanned
    ) {}

    public List<Request> search(Filter f, int limit, int offset) {
        Map<String, Object> p = new HashMap<>();
        String where = buildWhere(f, p);
        p.put("lim", limit);
        p.put("off", offset);
        return jdbc.query(SELECT + where + " ORDER BY r.received_at DESC, r.id DESC LIMIT :lim OFFSET :off",
                p, MAPPER);
    }

    public long count(Filter f) {
        Map<String, Object> p = new HashMap<>();
        String where = buildWhere(f, p);
        Long c = jdbc.queryForObject("""
                SELECT COUNT(*) FROM requests r
                JOIN projects p ON p.id = r.project_id
                JOIN buildings b ON b.id = r.building_id
                """ + where, p, Long.class);
        return c != null ? c : 0;
    }

    private String buildWhere(Filter f, Map<String, Object> p) {
        StringBuilder w = new StringBuilder(" WHERE 1=1");
        if (f.query() != null && !f.query().isBlank()) {
            w.append(" AND (r.number ILIKE :q OR r.external_number ILIKE :q OR b.address ILIKE :q"
                   + " OR r.apartment ILIKE :q OR r.description ILIKE :q)");
            p.put("q", "%" + f.query().trim() + "%");
        }
        if (f.projectIds() != null && !f.projectIds().isEmpty()) {
            w.append(" AND r.project_id IN (:projects)");
            p.put("projects", f.projectIds());
        }
        if (f.statuses() != null && !f.statuses().isEmpty()) {
            w.append(" AND r.status IN (:statuses)");
            p.put("statuses", f.statuses());
        }
        if (f.financialClasses() != null && !f.financialClasses().isEmpty()) {
            w.append(" AND r.financial_class IN (:fclasses)");
            p.put("fclasses", f.financialClasses());
        }
        if (f.buildingId() != null) {
            w.append(" AND r.building_id = :building");
            p.put("building", f.buildingId());
        }
        if (f.zheuId() != null) {
            w.append(" AND r.zheu_id = :zheu");
            p.put("zheu", f.zheuId());
        }
        if (f.clientId() != null) {
            w.append(" AND r.client_id = :client");
            p.put("client", f.clientId());
        }
        if (f.responsibleId() != null) {
            w.append(" AND r.responsible_employee_id = :resp");
            p.put("resp", f.responsibleId());
        }
        if (f.urgency() != null && !f.urgency().isBlank()) {
            w.append(" AND r.urgency = :urg");
            p.put("urg", f.urgency());
        }
        if (f.isWarranty() != null) {
            w.append(" AND r.is_warranty = :warr");
            p.put("warr", f.isWarranty());
        }
        if (f.customerClosed() != null) {
            w.append(" AND r.customer_closed = :cc");
            p.put("cc", f.customerClosed());
        }
        if (f.isClosed() != null) {
            w.append(" AND r.is_closed = :closed");
            p.put("closed", f.isClosed());
        }
        // Период можно считать по любой из трёх дат — вопрос «за какой месяц»
        // в отчётности имеет три разных ответа.
        String field = switch (f.dateField() == null ? "received" : f.dateField()) {
            case "done" -> "r.done_at";
            case "planned" -> "r.planned_date";
            case "accepted" -> "r.customer_accepted_at";
            default -> "r.received_at";
        };
        if (f.dateFrom() != null) {
            w.append(" AND ").append(field).append(" >= :dfrom");
            p.put("dfrom", f.dateFrom());
        }
        if (f.dateTo() != null) {
            w.append(" AND ").append(field).append(" < :dto");
            p.put("dto", f.dateTo().plusDays(1));
        }
        if (Boolean.TRUE.equals(f.overdue())) {
            w.append(" AND r.planned_date < CURRENT_DATE AND r.status NOT IN"
                   + " ('DONE','CUSTOMER_ACCEPTED','ACT_CREATED','ACT_SIGNED','CANCELLED')");
        }
        if (Boolean.TRUE.equals(f.unplanned())) {
            w.append(" AND r.planned_date IS NULL AND r.status NOT IN ('ACT_SIGNED','CANCELLED')");
        }
        return w.toString();
    }

    public Optional<Request> findById(Long id) {
        return jdbc.query(SELECT + " WHERE r.id = :id", Map.of("id", id), MAPPER).stream().findFirst();
    }

    public Optional<Request> findByExternal(String system, String externalNumber) {
        if (externalNumber == null || externalNumber.isBlank()) return Optional.empty();
        return jdbc.query(SELECT + " WHERE r.external_system = :s AND r.external_number = :n LIMIT 1",
                Map.of("s", system == null ? "" : system, "n", externalNumber), MAPPER).stream().findFirst();
    }

    public Optional<Request> findByNumber(String number) {
        return jdbc.query(SELECT + " WHERE r.number = :n", Map.of("n", number), MAPPER).stream().findFirst();
    }

    /** Следующий порядковый номер заявки внутри проекта и года. */
    public int nextSeq(Long projectId, int year) {
        Integer max = jdbc.queryForObject("""
                SELECT COALESCE(MAX(seq_in_project), 0) FROM requests
                WHERE project_id = :p AND EXTRACT(YEAR FROM received_at) = :y
                """, Map.of("p", projectId, "y", year), Integer.class);
        return (max != null ? max : 0) + 1;
    }

    public Request insert(Request r) {
        var kh = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO requests (number, seq_in_project, external_number, external_system, source_code,
                    client_id, project_id, building_id, zheu_id, apartment, entrance, entrance_is_manual,
                    entrance_auto, entrance_fix_reason, place_note, received_at_customer, received_at,
                    planned_date, done_at, customer_accepted_at, urgency, is_warranty, parent_request_id,
                    link_type, link_reason, status, financial_class, customer_closed, is_closed, closed_at,
                    cancel_reason, priority, priority_reason, description, act_wording, internal_comment,
                    responsible_employee_id, contact_id, contact_adhoc_name, contact_adhoc_phone,
                    report_link, photo_link, report_text, created_by)
                VALUES (:number, :seq, :extnum, :extsys, :source, :client, :project, :building, :zheu,
                    :apt, :entrance, :entManual, :entAuto, :entReason, :place, :recvCust,
                    COALESCE(:recv, NOW()), :planned, :done, :accepted, :urgency, :warranty, :parent,
                    :linkType, :linkReason, :status, :fclass, :custClosed, :closed, :closedAt,
                    :cancel, :priority, :priorityReason, :descr, :act, :comment, :resp, :contact,
                    :adhocName, :adhocPhone, :reportLink, :photoLink, :reportText, :author)
                """, params(r), kh, new String[]{"id"});
        return findById(((Number) kh.getKeys().get("id")).longValue()).orElseThrow();
    }

    public Request update(Long id, Request r) {
        jdbc.update("""
                UPDATE requests SET external_number = :extnum, external_system = :extsys,
                    source_code = :source, client_id = :client, project_id = :project,
                    building_id = :building, zheu_id = :zheu, apartment = :apt, entrance = :entrance,
                    entrance_is_manual = :entManual, entrance_auto = :entAuto,
                    entrance_fix_reason = :entReason, place_note = :place,
                    received_at_customer = :recvCust, planned_date = :planned, done_at = :done,
                    customer_accepted_at = :accepted, urgency = :urgency, is_warranty = :warranty,
                    financial_class = :fclass, customer_closed = :custClosed,
                    priority = :priority, priority_reason = :priorityReason, description = :descr,
                    act_wording = :act, internal_comment = :comment, responsible_employee_id = :resp,
                    contact_id = :contact, contact_adhoc_name = :adhocName,
                    contact_adhoc_phone = :adhocPhone, report_link = :reportLink,
                    photo_link = :photoLink, report_text = :reportText, updated_at = NOW()
                WHERE id = :id
                """, params(r).addValue("id", id));
        return findById(id).orElseThrow();
    }

    private MapSqlParameterSource params(Request r) {
        return new MapSqlParameterSource()
                .addValue("number", r.number()).addValue("seq", r.seqInProject())
                .addValue("extnum", r.externalNumber()).addValue("extsys", r.externalSystem())
                .addValue("source", r.sourceCode()).addValue("client", r.clientId())
                .addValue("project", r.projectId()).addValue("building", r.buildingId())
                .addValue("zheu", r.zheuId()).addValue("apt", r.apartment())
                .addValue("entrance", r.entrance()).addValue("entManual", r.entranceIsManual())
                .addValue("entAuto", r.entranceAuto()).addValue("entReason", r.entranceFixReason())
                .addValue("place", r.placeNote()).addValue("recvCust", r.receivedAtCustomer())
                .addValue("recv", r.receivedAt()).addValue("planned", r.plannedDate())
                .addValue("done", r.doneAt()).addValue("accepted", r.customerAcceptedAt())
                .addValue("urgency", r.urgency()).addValue("warranty", r.isWarranty())
                .addValue("parent", r.parentRequestId()).addValue("linkType", r.linkType())
                .addValue("linkReason", r.linkReason()).addValue("status", r.status())
                .addValue("fclass", r.financialClass()).addValue("custClosed", r.customerClosed())
                .addValue("closed", r.isClosed()).addValue("closedAt", r.closedAt())
                .addValue("cancel", r.cancelReason()).addValue("priority", r.priority())
                .addValue("priorityReason", r.priorityReason()).addValue("descr", r.description())
                .addValue("act", r.actWording()).addValue("comment", r.internalComment())
                .addValue("resp", r.responsibleEmployeeId()).addValue("contact", r.contactId())
                .addValue("adhocName", r.contactAdhocName()).addValue("adhocPhone", r.contactAdhocPhone())
                .addValue("reportLink", r.reportLink()).addValue("photoLink", r.photoLink())
                .addValue("reportText", r.reportText()).addValue("author", r.createdBy());
    }

    public void setStatus(Long id, String status) {
        jdbc.update("UPDATE requests SET status = :s, updated_at = NOW() WHERE id = :id",
                Map.of("s", status, "id", id));
    }

    public void setDoneAt(Long id, LocalDateTime doneAt) {
        Map<String, Object> p = new HashMap<>();
        p.put("d", doneAt);
        p.put("id", id);
        jdbc.update("UPDATE requests SET done_at = :d, updated_at = NOW() WHERE id = :id", p);
    }

    public void setClosed(Long id, boolean closed) {
        jdbc.update("UPDATE requests SET is_closed = :c, closed_at = CASE WHEN :c THEN NOW() ELSE NULL END,"
                + " updated_at = NOW() WHERE id = :id", Map.of("c", closed, "id", id));
    }

    public void setCancelReason(Long id, String reason) {
        Map<String, Object> p = new HashMap<>();
        p.put("r", reason);
        p.put("id", id);
        jdbc.update("UPDATE requests SET cancel_reason = :r WHERE id = :id", p);
    }

    // ---- позиции ------------------------------------------------------------

    private static final String ITEM_SELECT = """
            SELECT i.*, s.code AS sku_code, s.name AS sku_name, w.name AS work_object_name,
                   COALESCE((SELECT array_agg(a.employee_id) FROM request_item_assignees a
                             WHERE a.request_item_id = i.id), '{}') AS assignee_ids
            FROM request_items i
            JOIN sku s ON s.id = i.sku_id
            LEFT JOIN work_objects w ON w.id = i.work_object_id
            """;

    public List<RequestItem> items(Long requestId) {
        return jdbc.query(ITEM_SELECT + " WHERE i.request_id = :r ORDER BY i.sort_order, i.id",
                Map.of("r", requestId), ITEM_MAPPER);
    }

    public Optional<RequestItem> item(Long itemId) {
        return jdbc.query(ITEM_SELECT + " WHERE i.id = :id", Map.of("id", itemId), ITEM_MAPPER)
                .stream().findFirst();
    }

    public RequestItem insertItem(RequestItem i) {
        var kh = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO request_items (request_id, work_object_id, sku_id, unit_code, planned_qty,
                    declared_qty, fact_qty, remeasured_qty, accepted_qty, man_hours, reference_qty,
                    reference_unit_code, repair_type, status, note, sort_order)
                VALUES (:req, :wo, :sku, :unit, :planned, :declared, :fact, :remeasured, :accepted,
                        :hours, :refQty, :refUnit, :repair, :status, :note, :ord)
                """, itemParams(i), kh, new String[]{"id"});
        Long id = ((Number) kh.getKeys().get("id")).longValue();
        setAssignees(id, i.assigneeIds());
        return item(id).orElseThrow();
    }

    public RequestItem updateItem(Long id, RequestItem i) {
        jdbc.update("""
                UPDATE request_items SET work_object_id = :wo, sku_id = :sku, unit_code = :unit,
                    planned_qty = :planned, declared_qty = :declared, fact_qty = :fact,
                    remeasured_qty = :remeasured, accepted_qty = :accepted, man_hours = :hours,
                    reference_qty = :refQty, reference_unit_code = :refUnit, repair_type = :repair,
                    status = :status, note = :note, sort_order = :ord, updated_at = NOW()
                WHERE id = :id
                """, itemParams(i).addValue("id", id));
        setAssignees(id, i.assigneeIds());
        return item(id).orElseThrow();
    }

    public void deleteItem(Long id) {
        jdbc.update("DELETE FROM request_items WHERE id = :id", Map.of("id", id));
    }

    public void acceptItem(Long id, BigDecimal acceptedQty, String actor, String comment) {
        Map<String, Object> p = new HashMap<>();
        p.put("q", acceptedQty);
        p.put("a", actor);
        p.put("c", comment);
        p.put("id", id);
        jdbc.update("""
                UPDATE request_items SET accepted_qty = :q, status = 'ACCEPTED', accepted_by = :a,
                       accepted_at = NOW(), accept_comment = :c, updated_at = NOW()
                WHERE id = :id
                """, p);
    }

    public void setItemStatus(Long id, String status) {
        jdbc.update("UPDATE request_items SET status = :s, updated_at = NOW() WHERE id = :id",
                Map.of("s", status, "id", id));
    }

    private void setAssignees(Long itemId, List<Long> employeeIds) {
        if (employeeIds == null) return;
        jdbc.update("DELETE FROM request_item_assignees WHERE request_item_id = :i", Map.of("i", itemId));
        boolean first = true;
        for (Long e : employeeIds) {
            jdbc.update("""
                    INSERT INTO request_item_assignees (request_item_id, employee_id, is_responsible)
                    VALUES (:i, :e, :r) ON CONFLICT DO NOTHING
                    """, Map.of("i", itemId, "e", e, "r", first));
            first = false;
        }
    }

    private MapSqlParameterSource itemParams(RequestItem i) {
        return new MapSqlParameterSource()
                .addValue("req", i.requestId()).addValue("wo", i.workObjectId())
                .addValue("sku", i.skuId()).addValue("unit", i.unitCode())
                .addValue("planned", i.plannedQty()).addValue("declared", i.declaredQty())
                .addValue("fact", i.factQty()).addValue("remeasured", i.remeasuredQty())
                .addValue("accepted", i.acceptedQty()).addValue("hours", i.manHours())
                .addValue("refQty", i.referenceQty()).addValue("refUnit", i.referenceUnitCode())
                .addValue("repair", i.repairType())
                .addValue("status", i.status() != null ? i.status() : "PLANNED")
                .addValue("note", i.note()).addValue("ord", i.sortOrder());
    }

    // ---- история и события ---------------------------------------------------

    public void addStatusChange(Long requestId, String from, String to, String reason,
                                boolean override, String actor) {
        Map<String, Object> p = new HashMap<>();
        p.put("r", requestId);
        p.put("f", from);
        p.put("t", to);
        p.put("reason", reason);
        p.put("o", override);
        p.put("a", actor);
        jdbc.update("""
                INSERT INTO request_status_history (request_id, from_status, to_status, reason, is_override, actor)
                VALUES (:r, :f, :t, :reason, :o, :a)
                """, p);
    }

    public List<RequestStatusChange> statusHistory(Long requestId) {
        return jdbc.query("SELECT * FROM request_status_history WHERE request_id = :r ORDER BY created_at",
                Map.of("r", requestId), STATUS_MAPPER);
    }

    public void addEvent(Long requestId, String type, String text, String waitReason,
                         String payloadJson, String actor) {
        Map<String, Object> p = new HashMap<>();
        p.put("r", requestId);
        p.put("t", type);
        p.put("x", text);
        p.put("w", waitReason);
        p.put("j", payloadJson);
        p.put("a", actor);
        jdbc.update("""
                INSERT INTO request_events (request_id, event_type, text, wait_reason, payload_json, actor)
                VALUES (:r, :t, :x, :w, :j, :a)
                """, p);
    }

    public List<RequestEvent> events(Long requestId) {
        return jdbc.query("SELECT * FROM request_events WHERE request_id = :r ORDER BY created_at DESC",
                Map.of("r", requestId), EVENT_MAPPER);
    }

    // ---- чек-лист ------------------------------------------------------------

    public void initChecklist(Long requestId, Long configId) {
        jdbc.update("""
                INSERT INTO request_checklist (request_id, item_code, name, is_required)
                SELECT :r, item_code, name, is_required FROM project_config_checklist WHERE config_id = :c
                ON CONFLICT (request_id, item_code) DO NOTHING
                """, Map.of("r", requestId, "c", configId));
    }

    public List<ChecklistItem> checklist(Long requestId) {
        return jdbc.query("SELECT * FROM request_checklist WHERE request_id = :r ORDER BY id",
                Map.of("r", requestId), CHECKLIST_MAPPER);
    }

    public void setChecklistItem(Long requestId, String itemCode, boolean done,
                                 String skippedReason, String actor) {
        Map<String, Object> p = new HashMap<>();
        p.put("r", requestId);
        p.put("c", itemCode);
        p.put("d", done);
        p.put("s", skippedReason);
        p.put("a", actor);
        jdbc.update("""
                UPDATE request_checklist SET is_done = :d,
                    done_at = CASE WHEN :d THEN NOW() ELSE NULL END,
                    done_by = CASE WHEN :d THEN :a ELSE NULL END,
                    skipped_reason = :s
                WHERE request_id = :r AND item_code = :c
                """, p);
    }

    /** Невыполненные обязательные пункты — что мешает закрыть заявку. */
    public List<String> pendingChecklist(Long requestId) {
        return jdbc.queryForList("""
                SELECT name FROM request_checklist
                WHERE request_id = :r AND is_required AND NOT is_done
                  AND (skipped_reason IS NULL OR skipped_reason = '')
                ORDER BY id
                """, Map.of("r", requestId), String.class);
    }

    // ---- связи --------------------------------------------------------------

    public List<Request> linkedRequests(Long requestId) {
        return jdbc.query(SELECT + " WHERE r.parent_request_id = :id ORDER BY r.received_at",
                Map.of("id", requestId), MAPPER);
    }

    public List<Map<String, Object>> workObjects(Long requestId) {
        return jdbc.queryForList(
                "SELECT * FROM work_objects WHERE request_id = :r ORDER BY sort_order, id",
                Map.of("r", requestId));
    }

    public Long addWorkObject(Long requestId, String type, String name, String note) {
        var kh = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO work_objects (request_id, object_type, name, note) VALUES (:r, :t, :n, :note)
                """, new MapSqlParameterSource().addValue("r", requestId).addValue("t", type)
                     .addValue("n", name).addValue("note", note), kh, new String[]{"id"});
        return ((Number) kh.getKeys().get("id")).longValue();
    }

    /** Выезды, в которые включена заявка. */
    public List<Map<String, Object>> trips(Long requestId) {
        return jdbc.queryForList("""
                SELECT t.id, t.trip_date, t.status, v.plate, e.full_name AS responsible_name
                FROM trip_requests tr JOIN trips t ON t.id = tr.trip_id
                LEFT JOIN vehicles v ON v.id = t.vehicle_id
                LEFT JOIN employees e ON e.id = t.responsible_employee_id
                WHERE tr.request_id = :r ORDER BY t.trip_date DESC
                """, Map.of("r", requestId));
    }
}
