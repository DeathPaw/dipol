package ru.dipol.repository;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import ru.dipol.model.Client;
import ru.dipol.model.ClientContact;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class ClientRepository {

    private static final RowMapper<Client> MAPPER = (rs, n) -> new Client(
            rs.getLong("id"), rs.getString("client_type"), rs.getString("name"),
            rs.getString("short_name"), rs.getString("inn"), rs.getString("kpp"), rs.getString("ogrn"),
            rs.getString("legal_address"), rs.getString("actual_address"), rs.getString("bank_name"),
            rs.getString("bank_bik"), rs.getString("bank_account"), rs.getString("corr_account"),
            rs.getString("phone"), rs.getString("email"), rs.getString("comment"),
            rs.getBoolean("is_active"), rs.getObject("merged_into_id", Long.class));

    private static final RowMapper<ClientContact> CONTACT_MAPPER = (rs, n) -> new ClientContact(
            rs.getLong("id"), rs.getLong("client_id"), rs.getString("full_name"),
            rs.getString("position"), rs.getString("contact_role"), rs.getString("phone"),
            rs.getString("email"), rs.getString("messenger"), rs.getObject("zheu_id", Long.class),
            rs.getString("comment"), rs.getBoolean("is_active"));

    private final NamedParameterJdbcTemplate jdbc;

    public ClientRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Client> search(String query, String type, boolean includeInactive, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT * FROM clients WHERE 1=1");
        Map<String, Object> p = new HashMap<>();
        if (!includeInactive) sql.append(" AND is_active = TRUE");
        if (query != null && !query.isBlank()) {
            sql.append(" AND (name ILIKE :q OR inn ILIKE :q OR phone ILIKE :q OR email ILIKE :q)");
            p.put("q", "%" + query.trim() + "%");
        }
        if (type != null && !type.isBlank()) {
            sql.append(" AND client_type = :t");
            p.put("t", type);
        }
        sql.append(" ORDER BY name LIMIT :lim OFFSET :off");
        p.put("lim", limit);
        p.put("off", offset);
        return jdbc.query(sql.toString(), p, MAPPER);
    }

    public long count(String query, String type, boolean includeInactive) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM clients WHERE 1=1");
        Map<String, Object> p = new HashMap<>();
        if (!includeInactive) sql.append(" AND is_active = TRUE");
        if (query != null && !query.isBlank()) {
            sql.append(" AND (name ILIKE :q OR inn ILIKE :q OR phone ILIKE :q)");
            p.put("q", "%" + query.trim() + "%");
        }
        if (type != null && !type.isBlank()) {
            sql.append(" AND client_type = :t");
            p.put("t", type);
        }
        Long c = jdbc.queryForObject(sql.toString(), p, Long.class);
        return c != null ? c : 0;
    }

    public Optional<Client> findById(Long id) {
        return jdbc.query("SELECT * FROM clients WHERE id = :id", Map.of("id", id), MAPPER)
                .stream().findFirst();
    }

    public Optional<Client> findByName(String name) {
        return jdbc.query("SELECT * FROM clients WHERE lower(name) = lower(:n) LIMIT 1",
                Map.of("n", name), MAPPER).stream().findFirst();
    }

    public Client create(Client c) {
        var kh = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO clients (client_type, name, short_name, inn, kpp, ogrn, legal_address,
                    actual_address, bank_name, bank_bik, bank_account, corr_account, phone, email,
                    comment, is_active)
                VALUES (:type, :name, :short, :inn, :kpp, :ogrn, :la, :aa, :bank, :bik, :acc, :corr,
                        :phone, :email, :comment, :active)
                """, params(c), kh, new String[]{"id"});
        return findById(((Number) kh.getKeys().get("id")).longValue()).orElseThrow();
    }

    public Client update(Long id, Client c) {
        jdbc.update("""
                UPDATE clients SET client_type = :type, name = :name, short_name = :short, inn = :inn,
                       kpp = :kpp, ogrn = :ogrn, legal_address = :la, actual_address = :aa,
                       bank_name = :bank, bank_bik = :bik, bank_account = :acc, corr_account = :corr,
                       phone = :phone, email = :email, comment = :comment, is_active = :active,
                       updated_at = NOW()
                WHERE id = :id
                """, params(c).addValue("id", id));
        return findById(id).orElseThrow();
    }

    private MapSqlParameterSource params(Client c) {
        return new MapSqlParameterSource()
                .addValue("type", c.clientType()).addValue("name", c.name())
                .addValue("short", c.shortName()).addValue("inn", c.inn())
                .addValue("kpp", c.kpp()).addValue("ogrn", c.ogrn())
                .addValue("la", c.legalAddress()).addValue("aa", c.actualAddress())
                .addValue("bank", c.bankName()).addValue("bik", c.bankBik())
                .addValue("acc", c.bankAccount()).addValue("corr", c.corrAccount())
                .addValue("phone", c.phone()).addValue("email", c.email())
                .addValue("comment", c.comment()).addValue("active", c.isActive());
    }

    // ---- контактные лица ----------------------------------------------------

    public List<ClientContact> contacts(Long clientId, boolean includeInactive) {
        return jdbc.query("SELECT * FROM client_contacts WHERE client_id = :c"
                        + (includeInactive ? "" : " AND is_active = TRUE") + " ORDER BY full_name",
                Map.of("c", clientId), CONTACT_MAPPER);
    }

    public Optional<ClientContact> contact(Long id) {
        return jdbc.query("SELECT * FROM client_contacts WHERE id = :id", Map.of("id", id), CONTACT_MAPPER)
                .stream().findFirst();
    }

    public ClientContact createContact(ClientContact c) {
        var kh = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO client_contacts (client_id, full_name, position, contact_role, phone,
                    email, messenger, zheu_id, comment, is_active)
                VALUES (:cid, :name, :pos, :role, :phone, :email, :msg, :zheu, :comment, :active)
                """, contactParams(c), kh, new String[]{"id"});
        return contact(((Number) kh.getKeys().get("id")).longValue()).orElseThrow();
    }

    public ClientContact updateContact(Long id, ClientContact c) {
        jdbc.update("""
                UPDATE client_contacts SET full_name = :name, position = :pos, contact_role = :role,
                       phone = :phone, email = :email, messenger = :msg, zheu_id = :zheu,
                       comment = :comment, is_active = :active
                WHERE id = :id
                """, contactParams(c).addValue("id", id));
        return contact(id).orElseThrow();
    }

    private MapSqlParameterSource contactParams(ClientContact c) {
        return new MapSqlParameterSource()
                .addValue("cid", c.clientId()).addValue("name", c.fullName())
                .addValue("pos", c.position()).addValue("role", c.contactRole())
                .addValue("phone", c.phone()).addValue("email", c.email())
                .addValue("msg", c.messenger()).addValue("zheu", c.zheuId())
                .addValue("comment", c.comment()).addValue("active", c.isActive());
    }

    /** История клиента: проекты, объекты, заявки и агрегаты (US-O6). */
    public Map<String, Object> summary(Long clientId) {
        return jdbc.queryForMap("""
                SELECT
                  (SELECT COUNT(*) FROM projects p WHERE p.client_id = :c) AS projects_count,
                  (SELECT COUNT(*) FROM requests r WHERE r.client_id = :c) AS requests_total,
                  (SELECT COUNT(*) FROM requests r WHERE r.client_id = :c
                     AND r.status NOT IN ('ACT_SIGNED','CANCELLED')) AS requests_active,
                  (SELECT COUNT(DISTINCT r.building_id) FROM requests r WHERE r.client_id = :c) AS buildings_count,
                  (SELECT MAX(r.received_at) FROM requests r WHERE r.client_id = :c) AS last_request_at
                """, Map.of("c", clientId));
    }

    public List<Map<String, Object>> projects(Long clientId) {
        return jdbc.queryForList("""
                SELECT p.id, p.code, p.name, p.status, p.lifecycle_type, p.started_on, p.finished_on,
                       c.name AS config_name,
                       (SELECT COUNT(*) FROM requests r WHERE r.project_id = p.id) AS requests_count
                FROM projects p JOIN project_configs c ON c.id = p.config_id
                WHERE p.client_id = :c ORDER BY p.name
                """, Map.of("c", clientId));
    }
}
