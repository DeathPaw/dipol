package ru.dipol.files;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import ru.dipol.audit.CurrentUser;
import ru.dipol.exception.BusinessRuleException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Единое внутреннее файловое хранилище (US-K4).
 *
 * <p>Файл физически лежит один раз, а в ERP доступен из всех связанных
 * карточек через {@code file_links} — дублировать копии по папкам не нужно.
 * Права наследуются: файлы с признаком «служебный» не отдаются рабочим.
 */
@Service
public class FileStorageService {

    private final NamedParameterJdbcTemplate jdbc;
    private final Path root;

    public FileStorageService(NamedParameterJdbcTemplate jdbc,
                              @Value("${app.files.dir:./var/files}") String dir) {
        this.jdbc = jdbc;
        this.root = Paths.get(dir).toAbsolutePath().normalize();
    }

    public Map<String, Object> store(MultipartFile file, String docKind, boolean restricted) {
        if (file == null || file.isEmpty()) throw new BusinessRuleException("Пустой файл");
        LocalDate today = LocalDate.now();
        String key = today.getYear() + "/" + String.format("%02d", today.getMonthValue())
                + "/" + UUID.randomUUID() + suffix(file.getOriginalFilename());
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) {
            // Защита от «../» в имени: путь обязан остаться внутри хранилища.
            throw new BusinessRuleException("Недопустимое имя файла");
        }
        try {
            Files.createDirectories(target.getParent());
            file.transferTo(target);
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось сохранить файл: " + e.getMessage(), e);
        }

        var kh = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO files (storage_key, original_name, content_type, size_bytes, doc_kind,
                                   is_restricted, uploaded_by)
                VALUES (:k, :n, :t, :s, :d, :r, :u)
                """, new MapSqlParameterSource()
                        .addValue("k", key)
                        .addValue("n", file.getOriginalFilename())
                        .addValue("t", file.getContentType())
                        .addValue("s", file.getSize())
                        .addValue("d", docKind)
                        .addValue("r", restricted)
                        .addValue("u", CurrentUser.name()),
                kh, new String[]{"id"});
        Long id = ((Number) kh.getKeys().get("id")).longValue();
        return Map.of("id", id, "original_name", file.getOriginalFilename(), "size", file.getSize());
    }

    public Path resolve(String storageKey) {
        Path p = root.resolve(storageKey).normalize();
        if (!p.startsWith(root)) throw new BusinessRuleException("Недопустимый путь файла");
        return p;
    }

    public Map<String, Object> metadata(Long id) {
        return jdbc.queryForMap("SELECT * FROM files WHERE id = :id", Map.of("id", id));
    }

    /** Привязка файла к сущности. Один файл может висеть в нескольких карточках. */
    public void link(Long fileId, String entityType, Long entityId, String slot) {
        jdbc.update("""
                INSERT INTO file_links (file_id, entity_type, entity_id, slot)
                VALUES (:f, :t, :i, :s) ON CONFLICT DO NOTHING
                """, Map.of("f", fileId, "t", entityType, "i", entityId, "s", slot != null ? slot : ""));
    }

    public void unlink(Long fileId, String entityType, Long entityId) {
        jdbc.update("""
                DELETE FROM file_links WHERE file_id = :f AND entity_type = :t AND entity_id = :i
                """, Map.of("f", fileId, "t", entityType, "i", entityId));
    }

    public List<Map<String, Object>> forEntity(String entityType, Long entityId, boolean includeRestricted) {
        return jdbc.queryForList("""
                SELECT f.*, l.slot FROM file_links l JOIN files f ON f.id = l.file_id
                WHERE l.entity_type = :t AND l.entity_id = :i
                """ + (includeRestricted ? "" : " AND NOT f.is_restricted")
                + " ORDER BY l.slot, f.created_at DESC",
                Map.of("t", entityType, "i", entityId));
    }

    /** Все карточки, из которых доступен файл — видно, что копий не появилось. */
    public List<Map<String, Object>> linksOf(Long fileId) {
        return jdbc.queryForList("SELECT entity_type, entity_id, slot FROM file_links WHERE file_id = :f",
                Map.of("f", fileId));
    }

    private static String suffix(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot) : "";
    }
}
