package ru.dipol.service;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.dipol.audit.CurrentUser;
import ru.dipol.exception.BusinessRuleException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Единый складской контур.
 *
 * <p>Всё проходит через один журнал движений, включая сценарий «с колёс»:
 * материал оформляется поступлением и тут же связанным списанием на работу,
 * без физического хранения (US-I5). Отмена — обратное движение, исходная
 * операция не удаляется (US-I6).
 *
 * <p>Средняя учётная цена считается как скользящая средняя по журналу:
 * при поступлении пересчитывается от остатка и суммы, при списании берётся
 * текущая. Возврат восстанавливает количество и стоимость обратным движением,
 * поэтому результат воспроизводим из журнала.
 */
@Service
public class StockService {

    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService audit;

    public StockService(NamedParameterJdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record Balance(BigDecimal qty, BigDecimal amount, BigDecimal avgPrice) {}

    /** Остаток и средняя цена материала на текущий момент. */
    public Balance balance(Long materialId) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT
                  COALESCE(SUM(CASE WHEN movement_type IN ('RECEIPT','RETURN') THEN qty
                                    WHEN movement_type = 'ISSUE' THEN -qty ELSE 0 END), 0) AS qty,
                  COALESCE(SUM(CASE WHEN movement_type IN ('RECEIPT','RETURN') THEN COALESCE(amount,0)
                                    WHEN movement_type = 'ISSUE' THEN -COALESCE(amount,0) ELSE 0 END), 0) AS amount
                FROM stock_movements WHERE material_id = :m
                """, Map.of("m", materialId));
        BigDecimal qty = new BigDecimal(String.valueOf(row.get("qty")));
        BigDecimal amount = new BigDecimal(String.valueOf(row.get("amount")));
        BigDecimal avg = qty.compareTo(BigDecimal.ZERO) > 0
                ? amount.divide(qty, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        return new Balance(qty, amount, avg);
    }

    @Transactional
    public Long receipt(Map<String, Object> body) {
        Long materialId = num(body.get("material_id"));
        BigDecimal qty = dec(body.get("qty"));
        if (materialId == null) throw new BusinessRuleException("Не указан материал");
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessRuleException("Количество поступления должно быть положительным");
        }
        BigDecimal price = dec(body.get("price"));
        BigDecimal amount = dec(body.get("amount"));
        if (amount == null && price != null) amount = price.multiply(qty).setScale(2, RoundingMode.HALF_UP);
        if (price == null && amount != null && qty.compareTo(BigDecimal.ZERO) > 0) {
            price = amount.divide(qty, 2, RoundingMode.HALF_UP);
        }

        Long id = insert("RECEIPT", body, qty, price, amount, null);
        // История цены ведётся отдельно от движений: по ней смотрят динамику
        // закупки, не пересобирая журнал.
        if (price != null) {
            Map<String, Object> p = new HashMap<>();
            p.put("m", materialId);
            p.put("s", num(body.get("supplier_id")));
            p.put("d", java.time.LocalDate.now());
            p.put("p", price);
            p.put("o", body.get("order_number"));
            jdbc.update("""
                    INSERT INTO material_prices (material_id, supplier_id, priced_on, price, order_number)
                    VALUES (:m, :s, :d, :p, :o)
                    """, p);
        }
        audit.action("STOCK", id, "RECEIPT", "материал " + materialId + ", " + qty);
        return id;
    }

    /**
     * Списание. Списать можно только то, что числится в складском контуре:
     * иначе себестоимость собирается из материала, которого не было (US-I6).
     */
    @Transactional
    public Long issue(Map<String, Object> body) {
        Long materialId = num(body.get("material_id"));
        BigDecimal qty = dec(body.get("qty"));
        if (materialId == null) throw new BusinessRuleException("Не указан материал");
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessRuleException("Количество списания должно быть положительным");
        }
        Balance b = balance(materialId);
        if (b.qty().compareTo(qty) < 0) {
            throw new BusinessRuleException("Недостаточно материала на остатке: есть "
                    + b.qty().stripTrailingZeros().toPlainString() + ", списывается "
                    + qty.stripTrailingZeros().toPlainString());
        }
        BigDecimal price = b.avgPrice();
        BigDecimal amount = price.multiply(qty).setScale(2, RoundingMode.HALF_UP);
        Long id = insert("ISSUE", body, qty, price, amount, null);
        audit.action("STOCK", id, "ISSUE", "материал " + materialId + ", " + qty);
        return id;
    }

    /**
     * «С колёс»: поступление и немедленное списание одной операцией. Обе записи
     * остаются в журнале и ссылаются друг на друга.
     */
    @Transactional
    public Map<String, Object> onWheels(Map<String, Object> body) {
        Map<String, Object> receiptBody = new HashMap<>(body);
        receiptBody.put("on_wheels", true);
        Long receiptId = receipt(receiptBody);

        Map<String, Object> issueBody = new HashMap<>(body);
        issueBody.put("on_wheels", true);
        Long issueId = issue(issueBody);

        jdbc.update("UPDATE stock_movements SET linked_movement_id = :i WHERE id = :r",
                Map.of("i", issueId, "r", receiptId));
        jdbc.update("UPDATE stock_movements SET linked_movement_id = :r WHERE id = :i",
                Map.of("r", receiptId, "i", issueId));
        return Map.of("receipt_id", receiptId, "issue_id", issueId);
    }

    /**
     * Отмена движения — обратная запись. Частичный возврат корректирует только
     * отменяемую часть; исходная операция остаётся нетронутой.
     */
    @Transactional
    public Long reverse(Long movementId, BigDecimal qty, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleException("Отмена движения требует основания");
        }
        Map<String, Object> src = jdbc.queryForMap(
                "SELECT * FROM stock_movements WHERE id = :id", Map.of("id", movementId));
        BigDecimal srcQty = new BigDecimal(String.valueOf(src.get("qty")));
        BigDecimal reverseQty = qty != null ? qty : srcQty;
        if (reverseQty.compareTo(srcQty) > 0) {
            throw new BusinessRuleException("Нельзя отменить больше, чем было в исходной операции");
        }
        BigDecimal alreadyReversed = jdbc.queryForObject(
                "SELECT COALESCE(SUM(qty),0) FROM stock_movements WHERE reverses_id = :id",
                Map.of("id", movementId), BigDecimal.class);
        if (alreadyReversed != null && alreadyReversed.add(reverseQty).compareTo(srcQty) > 0) {
            throw new BusinessRuleException("Операция уже отменена на "
                    + alreadyReversed.stripTrailingZeros().toPlainString());
        }

        String srcType = String.valueOf(src.get("movement_type"));
        String reverseType = switch (srcType) {
            case "RECEIPT" -> "ISSUE";
            case "ISSUE" -> "RETURN";
            case "RETURN" -> "ISSUE";
            default -> "ADJUST";
        };
        BigDecimal price = src.get("price") != null
                ? new BigDecimal(String.valueOf(src.get("price"))) : null;
        BigDecimal amount = price != null
                ? price.multiply(reverseQty).setScale(2, RoundingMode.HALF_UP) : null;

        Map<String, Object> body = new HashMap<>();
        body.put("material_id", src.get("material_id"));
        body.put("unit_code", src.get("unit_code"));
        body.put("supplier_id", src.get("supplier_id"));
        body.put("project_id", src.get("project_id"));
        body.put("request_id", src.get("request_id"));
        body.put("request_item_id", src.get("request_item_id"));
        body.put("building_id", src.get("building_id"));
        body.put("employee_id", src.get("employee_id"));
        body.put("comment", "Сторно движения #" + movementId + ": " + reason);

        Long id = insert(reverseType, body, reverseQty, price, amount, movementId);
        audit.action("STOCK", movementId, "REVERSE", reason + " → движение #" + id);
        return id;
    }

    private Long insert(String type, Map<String, Object> body, BigDecimal qty,
                        BigDecimal price, BigDecimal amount, Long reversesId) {
        var kh = new GeneratedKeyHolder();
        var p = new MapSqlParameterSource()
                .addValue("t", type)
                .addValue("m", num(body.get("material_id")))
                .addValue("q", qty)
                .addValue("u", body.getOrDefault("unit_code", "PCS"))
                .addValue("price", price)
                .addValue("amount", amount)
                .addValue("at", body.get("moved_at") != null
                        ? LocalDateTime.parse(String.valueOf(body.get("moved_at"))) : LocalDateTime.now())
                .addValue("sup", num(body.get("supplier_id")))
                .addValue("line", num(body.get("order_line_id")))
                .addValue("proj", num(body.get("project_id")))
                .addValue("req", num(body.get("request_id")))
                .addValue("item", num(body.get("request_item_id")))
                .addValue("bld", num(body.get("building_id")))
                .addValue("emp", num(body.get("employee_id")))
                .addValue("wheels", Boolean.TRUE.equals(body.get("on_wheels")))
                .addValue("rev", reversesId)
                .addValue("c", body.get("comment"))
                .addValue("author", CurrentUser.name());
        jdbc.update("""
                INSERT INTO stock_movements (movement_type, material_id, qty, unit_code, price, amount,
                    moved_at, supplier_id, order_line_id, project_id, request_id, request_item_id,
                    building_id, employee_id, on_wheels, reverses_id, comment, created_by)
                VALUES (:t, :m, :q, :u, :price, :amount, :at, :sup, :line, :proj, :req, :item,
                        :bld, :emp, :wheels, :rev, :c, :author)
                """, p, kh, new String[]{"id"});
        return ((Number) kh.getKeys().get("id")).longValue();
    }

    /** Остатки по всем материалам — для страницы склада. */
    public List<Map<String, Object>> balances() {
        return jdbc.queryForList("""
                SELECT m.id, m.name, m.unit_code, g.name AS group_name,
                  COALESCE(SUM(CASE WHEN sm.movement_type IN ('RECEIPT','RETURN') THEN sm.qty
                                    WHEN sm.movement_type = 'ISSUE' THEN -sm.qty ELSE 0 END), 0) AS qty,
                  COALESCE(SUM(CASE WHEN sm.movement_type IN ('RECEIPT','RETURN') THEN COALESCE(sm.amount,0)
                                    WHEN sm.movement_type = 'ISSUE' THEN -COALESCE(sm.amount,0) ELSE 0 END), 0) AS amount
                FROM materials m
                LEFT JOIN material_groups g ON g.id = m.group_id
                LEFT JOIN stock_movements sm ON sm.material_id = m.id
                WHERE m.is_active
                GROUP BY m.id, m.name, m.unit_code, g.name
                HAVING COALESCE(SUM(CASE WHEN sm.movement_type IN ('RECEIPT','RETURN') THEN sm.qty
                                         WHEN sm.movement_type = 'ISSUE' THEN -sm.qty ELSE 0 END), 0) <> 0
                ORDER BY m.name
                """, Map.of());
    }

    private static Long num(Object v) {
        return v == null ? null : ((Number) v).longValue();
    }

    private static BigDecimal dec(Object v) {
        if (v == null || String.valueOf(v).isBlank()) return null;
        return new BigDecimal(String.valueOf(v));
    }
}
