package ru.dipol.controller;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.web.bind.annotation.*;
import ru.dipol.audit.CurrentUser;
import ru.dipol.exception.BusinessRuleException;
import ru.dipol.service.AuditService;
import ru.dipol.service.PermissionService;
import ru.dipol.service.StockService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Номенклатура, поставщики, заказы, склад и разнесение закупок по проектам. */
@RestController
@RequestMapping("/api/materials")
public class MaterialController {

    private final NamedParameterJdbcTemplate jdbc;
    private final StockService stock;
    private final PermissionService permissions;
    private final AuditService audit;

    public MaterialController(NamedParameterJdbcTemplate jdbc, StockService stock,
                              PermissionService permissions, AuditService audit) {
        this.jdbc = jdbc;
        this.stock = stock;
        this.permissions = permissions;
        this.audit = audit;
    }

    // ---- номенклатура -------------------------------------------------------

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String q,
                                          @RequestParam(defaultValue = "false") boolean includeInactive) {
        StringBuilder sql = new StringBuilder("""
                SELECT m.*, g.name AS group_name FROM materials m
                LEFT JOIN material_groups g ON g.id = m.group_id WHERE 1=1
                """);
        Map<String, Object> p = new HashMap<>();
        if (!includeInactive) sql.append(" AND m.is_active");
        if (q != null && !q.isBlank()) {
            sql.append(" AND (m.name ILIKE :q OR m.code ILIKE :q"
                     + " OR EXISTS (SELECT 1 FROM material_aliases a WHERE a.material_id = m.id AND a.alias ILIKE :q))");
            p.put("q", "%" + q.trim() + "%");
        }
        sql.append(" ORDER BY m.name");
        return jdbc.queryForList(sql.toString(), p);
    }

    @GetMapping("/groups")
    public List<Map<String, Object>> groups() {
        return jdbc.queryForList("SELECT * FROM material_groups ORDER BY sort_order, name", Map.of());
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        permissions.require("MATERIAL_EDIT", null);
        var kh = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO materials (code, name, group_id, unit_code, external_1c_code, area_per_unit, note)
                VALUES (:code, :name, :g, :u, :ext, :area, :note)
                """, new MapSqlParameterSource()
                        .addValue("code", body.get("code"))
                        .addValue("name", body.get("name"))
                        .addValue("g", num(body.get("group_id")))
                        .addValue("u", body.getOrDefault("unit_code", "PCS"))
                        .addValue("ext", body.get("external_1c_code"))
                        .addValue("area", dec(body.get("area_per_unit")))
                        .addValue("note", body.get("note")),
                kh, new String[]{"id"});
        Long id = ((Number) kh.getKeys().get("id")).longValue();
        audit.created("MATERIAL", id);
        return Map.of("id", id);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        permissions.require("MATERIAL_EDIT", null);
        jdbc.update("""
                UPDATE materials SET code = :code, name = :name, group_id = :g, unit_code = :u,
                       external_1c_code = :ext, area_per_unit = :area, is_active = :active, note = :note
                WHERE id = :id
                """, new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("code", body.get("code"))
                        .addValue("name", body.get("name"))
                        .addValue("g", num(body.get("group_id")))
                        .addValue("u", body.getOrDefault("unit_code", "PCS"))
                        .addValue("ext", body.get("external_1c_code"))
                        .addValue("area", dec(body.get("area_per_unit")))
                        .addValue("active", !Boolean.FALSE.equals(body.get("is_active")))
                        .addValue("note", body.get("note")));
        audit.action("MATERIAL", id, "UPDATE", null);
        return Map.of("status", "updated");
    }

    /**
     * Объединение дублей номенклатуры. Движения переезжают на целевую позицию,
     * старое название остаётся алиасом — история не теряется (US-I1).
     */
    @PostMapping("/{id}/merge")
    public Map<String, Object> merge(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        permissions.require("MATERIAL_EDIT", null);
        Long target = num(body.get("target_id"));
        if (target == null || target.equals(id)) {
            throw new BusinessRuleException("Нужна другая позиция для объединения");
        }
        var src = jdbc.queryForMap("SELECT * FROM materials WHERE id = :id", Map.of("id", id));
        Map<String, Object> p = Map.of("s", id, "t", target);
        jdbc.update("UPDATE stock_movements SET material_id = :t WHERE material_id = :s", p);
        jdbc.update("UPDATE material_prices SET material_id = :t WHERE material_id = :s", p);
        jdbc.update("UPDATE purchase_order_lines SET material_id = :t WHERE material_id = :s", p);
        jdbc.update("UPDATE material_plans SET material_id = :t WHERE material_id = :s", p);
        Map<String, Object> alias = new HashMap<>();
        alias.put("m", target);
        alias.put("a", src.get("name"));
        alias.put("k", String.valueOf(src.get("name")).toLowerCase());
        jdbc.update("""
                INSERT INTO material_aliases (material_id, alias, alias_key, source)
                VALUES (:m, :a, :k, 'MERGE') ON CONFLICT (alias_key) DO NOTHING
                """, alias);
        jdbc.update("UPDATE materials SET is_active = FALSE, merged_into_id = :t WHERE id = :s", p);
        audit.action("MATERIAL", id, "MERGE", "объединён с id=" + target);
        return Map.of("status", "merged");
    }

    @GetMapping("/{id}/prices")
    public List<Map<String, Object>> prices(@PathVariable Long id) {
        return jdbc.queryForList("""
                SELECT mp.*, s.name AS supplier_name FROM material_prices mp
                LEFT JOIN suppliers s ON s.id = mp.supplier_id
                WHERE mp.material_id = :m ORDER BY mp.priced_on DESC
                """, Map.of("m", id));
    }

    // ---- поставщики ---------------------------------------------------------

    @GetMapping("/suppliers")
    public List<Map<String, Object>> suppliers(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return jdbc.queryForList("SELECT * FROM suppliers"
                + (includeInactive ? "" : " WHERE is_active") + " ORDER BY name", Map.of());
    }

    @PostMapping("/suppliers")
    public Map<String, Object> createSupplier(@RequestBody Map<String, Object> body) {
        permissions.require("MATERIAL_EDIT", null);
        var kh = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO suppliers (name, inn, phone, email, contact, terms, note)
                VALUES (:n, :i, :p, :e, :c, :t, :note)
                """, new MapSqlParameterSource()
                        .addValue("n", body.get("name")).addValue("i", body.get("inn"))
                        .addValue("p", body.get("phone")).addValue("e", body.get("email"))
                        .addValue("c", body.get("contact")).addValue("t", body.get("terms"))
                        .addValue("note", body.get("note")),
                kh, new String[]{"id"});
        return Map.of("id", ((Number) kh.getKeys().get("id")).longValue());
    }

    @PutMapping("/suppliers/{id}")
    public Map<String, Object> updateSupplier(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        permissions.require("MATERIAL_EDIT", null);
        jdbc.update("""
                UPDATE suppliers SET name = :n, inn = :i, phone = :p, email = :e, contact = :c,
                       terms = :t, is_active = :a, note = :note
                WHERE id = :id
                """, new MapSqlParameterSource()
                        .addValue("id", id).addValue("n", body.get("name")).addValue("i", body.get("inn"))
                        .addValue("p", body.get("phone")).addValue("e", body.get("email"))
                        .addValue("c", body.get("contact")).addValue("t", body.get("terms"))
                        .addValue("a", !Boolean.FALSE.equals(body.get("is_active")))
                        .addValue("note", body.get("note")));
        return Map.of("status", "updated");
    }

    // ---- заказы поставщику --------------------------------------------------

    @GetMapping("/orders")
    public List<Map<String, Object>> orders(@RequestParam(required = false) LocalDate from,
                                            @RequestParam(required = false) LocalDate to,
                                            @RequestParam(required = false) Long supplierId) {
        StringBuilder sql = new StringBuilder("""
                SELECT o.*, s.name AS supplier_name,
                       (SELECT COUNT(*) FROM purchase_order_lines l WHERE l.order_id = o.id) AS lines_count,
                       (SELECT COALESCE(SUM(l.amount),0) FROM purchase_order_lines l WHERE l.order_id = o.id) AS lines_total
                FROM purchase_orders o LEFT JOIN suppliers s ON s.id = o.supplier_id WHERE 1=1
                """);
        Map<String, Object> p = new HashMap<>();
        if (from != null) {
            sql.append(" AND o.ordered_at >= :f");
            p.put("f", from.atStartOfDay());
        }
        if (to != null) {
            sql.append(" AND o.ordered_at < :t");
            p.put("t", to.plusDays(1).atStartOfDay());
        }
        if (supplierId != null) {
            sql.append(" AND o.supplier_id = :s");
            p.put("s", supplierId);
        }
        sql.append(" ORDER BY o.ordered_at DESC");
        return jdbc.queryForList(sql.toString(), p);
    }

    @GetMapping("/orders/{id}")
    public Map<String, Object> order(@PathVariable Long id) {
        return Map.of(
                "order", jdbc.queryForMap("SELECT * FROM purchase_orders WHERE id = :id", Map.of("id", id)),
                "lines", jdbc.queryForList("""
                        SELECT l.*, m.name AS material_name FROM purchase_order_lines l
                        JOIN materials m ON m.id = l.material_id WHERE l.order_id = :id ORDER BY l.id
                        """, Map.of("id", id)),
                "allocations", jdbc.queryForList("""
                        SELECT a.*, p.code AS project_code, b.address AS building_address
                        FROM material_allocations a
                        LEFT JOIN projects p ON p.id = a.project_id
                        LEFT JOIN buildings b ON b.id = a.building_id
                        WHERE a.order_line_id IN (SELECT id FROM purchase_order_lines WHERE order_id = :id)
                        """, Map.of("id", id))
        );
    }

    /**
     * Заказ со строками. Номер заказа больше не дублируется ради разнесения по
     * проектам — для этого есть отдельные записи распределения (US-I3, US-I4).
     */
    @PostMapping("/orders")
    @SuppressWarnings("unchecked")
    public Map<String, Object> createOrder(@RequestBody Map<String, Object> body) {
        permissions.require("MATERIAL_EDIT", null);
        var kh = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO purchase_orders (number, supplier_id, ordered_at, note, created_by)
                VALUES (:n, :s, :d, :note, :a)
                """, new MapSqlParameterSource()
                        .addValue("n", body.get("number"))
                        .addValue("s", num(body.get("supplier_id")))
                        .addValue("d", body.get("ordered_at") != null
                                ? java.time.LocalDateTime.parse(String.valueOf(body.get("ordered_at")))
                                : java.time.LocalDateTime.now())
                        .addValue("note", body.get("note"))
                        .addValue("a", CurrentUser.name()),
                kh, new String[]{"id"});
        Long orderId = ((Number) kh.getKeys().get("id")).longValue();

        List<Map<String, Object>> lines = (List<Map<String, Object>>) body.getOrDefault("lines", List.of());
        BigDecimal total = BigDecimal.ZERO;
        for (var l : lines) {
            BigDecimal qty = dec(l.get("qty"));
            BigDecimal price = dec(l.get("price"));
            BigDecimal amount = dec(l.get("amount"));
            if (amount == null && qty != null && price != null) amount = qty.multiply(price);
            if (amount != null) total = total.add(amount);
            Map<String, Object> p = new HashMap<>();
            p.put("o", orderId);
            p.put("m", num(l.get("material_id")));
            p.put("q", qty);
            p.put("u", l.getOrDefault("unit_code", "PCS"));
            p.put("p", price);
            p.put("a", amount);
            p.put("note", l.get("note"));
            jdbc.update("""
                    INSERT INTO purchase_order_lines (order_id, material_id, qty, unit_code, price, amount, note)
                    VALUES (:o, :m, :q, :u, :p, :a, :note)
                    """, p);
        }
        jdbc.update("UPDATE purchase_orders SET total_amount = :t WHERE id = :id",
                Map.of("t", total, "id", orderId));
        audit.created("PURCHASE_ORDER", orderId);
        return Map.of("id", orderId, "total_amount", total);
    }

    /** Разнесение строки заказа по проектам и адресам. */
    @PutMapping("/order-lines/{lineId}/allocations")
    public Map<String, Object> allocate(@PathVariable Long lineId,
                                        @RequestBody List<Map<String, Object>> allocations) {
        permissions.require("MATERIAL_EDIT", null);
        var line = jdbc.queryForMap("SELECT * FROM purchase_order_lines WHERE id = :id", Map.of("id", lineId));
        BigDecimal lineQty = new BigDecimal(String.valueOf(line.get("qty")));
        BigDecimal sum = allocations.stream().map(a -> dec(a.get("qty")))
                .filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);

        jdbc.update("DELETE FROM material_allocations WHERE order_line_id = :l", Map.of("l", lineId));
        for (var a : allocations) {
            Map<String, Object> p = new HashMap<>();
            p.put("l", lineId);
            p.put("proj", num(a.get("project_id")));
            p.put("b", num(a.get("building_id")));
            p.put("r", num(a.get("request_id")));
            p.put("q", dec(a.get("qty")));
            p.put("amt", dec(a.get("amount")));
            p.put("basis", a.get("basis"));
            p.put("note", a.get("note"));
            p.put("author", CurrentUser.name());
            jdbc.update("""
                    INSERT INTO material_allocations (order_line_id, project_id, building_id, request_id,
                                                      qty, amount, basis, note, created_by)
                    VALUES (:l, :proj, :b, :r, :q, :amt, :basis, :note, :author)
                    """, p);
        }
        // Превышение не блокируем: бывает, что фактически израсходовано больше,
        // чем в строке заказа, и это видно в отчёте — но пользователь должен знать.
        String warning = sum.compareTo(lineQty) > 0
                ? "Распределено " + sum + " при количестве в заказе " + lineQty : null;
        Map<String, Object> result = new HashMap<>();
        result.put("allocated", sum);
        result.put("line_qty", lineQty);
        result.put("warning", warning);
        return result;
    }

    // ---- склад --------------------------------------------------------------

    @GetMapping("/stock/balances")
    public List<Map<String, Object>> balances() {
        return stock.balances();
    }

    @GetMapping("/stock/movements")
    public List<Map<String, Object>> movements(@RequestParam(required = false) Long materialId,
                                               @RequestParam(required = false) Long projectId,
                                               @RequestParam(required = false) Long requestId,
                                               @RequestParam(required = false) LocalDate from,
                                               @RequestParam(required = false) LocalDate to,
                                               @RequestParam(defaultValue = "500") int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT sm.*, m.name AS material_name, p.code AS project_code, r.number AS request_number,
                       s.name AS supplier_name, b.address AS building_address
                FROM stock_movements sm
                JOIN materials m ON m.id = sm.material_id
                LEFT JOIN projects p ON p.id = sm.project_id
                LEFT JOIN requests r ON r.id = sm.request_id
                LEFT JOIN suppliers s ON s.id = sm.supplier_id
                LEFT JOIN buildings b ON b.id = sm.building_id
                WHERE 1=1
                """);
        Map<String, Object> pr = new HashMap<>();
        if (materialId != null) {
            sql.append(" AND sm.material_id = :m");
            pr.put("m", materialId);
        }
        if (projectId != null) {
            sql.append(" AND sm.project_id = :p");
            pr.put("p", projectId);
        }
        if (requestId != null) {
            sql.append(" AND sm.request_id = :r");
            pr.put("r", requestId);
        }
        if (from != null) {
            sql.append(" AND sm.moved_at >= :f");
            pr.put("f", from.atStartOfDay());
        }
        if (to != null) {
            sql.append(" AND sm.moved_at < :t");
            pr.put("t", to.plusDays(1).atStartOfDay());
        }
        sql.append(" ORDER BY sm.moved_at DESC, sm.id DESC LIMIT :lim");
        pr.put("lim", limit);
        return jdbc.queryForList(sql.toString(), pr);
    }

    @PostMapping("/stock/receipt")
    public Map<String, Object> receipt(@RequestBody Map<String, Object> body) {
        permissions.require("MATERIAL_EDIT", null);
        return Map.of("id", stock.receipt(body));
    }

    @PostMapping("/stock/issue")
    public Map<String, Object> issue(@RequestBody Map<String, Object> body) {
        permissions.require("MATERIAL_ISSUE", "Списание материалов требует отдельного права");
        return Map.of("id", stock.issue(body));
    }

    @PostMapping("/stock/on-wheels")
    public Map<String, Object> onWheels(@RequestBody Map<String, Object> body) {
        permissions.require("MATERIAL_ISSUE", "Списание материалов требует отдельного права");
        return stock.onWheels(body);
    }

    @PostMapping("/stock/movements/{id}/reverse")
    public Map<String, Object> reverse(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        permissions.require("MATERIAL_ISSUE", null);
        return Map.of("id", stock.reverse(id, dec(body.get("qty")), (String) body.get("reason")));
    }

    // ---- план материалов ----------------------------------------------------

    @GetMapping("/plans")
    public List<Map<String, Object>> plans(@RequestParam Long projectId) {
        return jdbc.queryForList("""
                SELECT mp.*, m.name AS material_name, b.address AS building_address
                FROM material_plans mp JOIN materials m ON m.id = mp.material_id
                LEFT JOIN buildings b ON b.id = mp.building_id
                WHERE mp.project_id = :p ORDER BY m.name
                """, Map.of("p", projectId));
    }

    @PostMapping("/plans")
    public Map<String, Object> createPlan(@RequestBody Map<String, Object> body) {
        permissions.require("MATERIAL_EDIT", null);
        Map<String, Object> p = new HashMap<>();
        p.put("proj", num(body.get("project_id")));
        p.put("b", num(body.get("building_id")));
        p.put("r", num(body.get("request_id")));
        p.put("m", num(body.get("material_id")));
        p.put("q", dec(body.get("qty")));
        p.put("u", body.getOrDefault("unit_code", "PCS"));
        p.put("a", dec(body.get("amount")));
        p.put("note", body.get("note"));
        jdbc.update("""
                INSERT INTO material_plans (project_id, building_id, request_id, material_id, qty,
                                            unit_code, amount, note)
                VALUES (:proj, :b, :r, :m, :q, :u, :a, :note)
                """, p);
        return Map.of("status", "created");
    }

    private static Long num(Object v) {
        return v == null ? null : ((Number) v).longValue();
    }

    private static BigDecimal dec(Object v) {
        if (v == null || String.valueOf(v).isBlank()) return null;
        return new BigDecimal(String.valueOf(v));
    }
}
