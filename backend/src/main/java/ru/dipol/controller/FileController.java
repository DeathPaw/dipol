package ru.dipol.controller;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import ru.dipol.audit.CurrentUser;
import ru.dipol.exception.ForbiddenException;
import ru.dipol.files.FileStorageService;
import ru.dipol.service.AuditService;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Файловое хранилище: загрузка, скачивание и привязка к карточкам ERP. */
@RestController
@RequestMapping("/api/files")
public class FileController {

    private final FileStorageService storage;
    private final AuditService audit;

    public FileController(FileStorageService storage, AuditService audit) {
        this.storage = storage;
        this.audit = audit;
    }

    @PostMapping
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file,
                                      @RequestParam(required = false) String docKind,
                                      @RequestParam(defaultValue = "false") boolean restricted,
                                      @RequestParam(required = false) String entityType,
                                      @RequestParam(required = false) Long entityId,
                                      @RequestParam(required = false) String slot) {
        var meta = storage.store(file, docKind, restricted);
        Long id = (Long) meta.get("id");
        if (entityType != null && entityId != null) {
            storage.link(id, entityType, entityId, slot);
        }
        audit.action("FILE", id, "UPLOAD", String.valueOf(meta.get("original_name")));
        return meta;
    }

    @GetMapping("/{id}")
    public ResponseEntity<Resource> download(@PathVariable Long id) {
        Map<String, Object> meta = storage.metadata(id);
        // Служебные и финансовые документы рабочим не отдаём (US-K4).
        if (Boolean.TRUE.equals(meta.get("is_restricted")) && CurrentUser.hasRole("WORKER")) {
            throw new ForbiddenException("Документ недоступен для этой роли");
        }
        var path = storage.resolve(String.valueOf(meta.get("storage_key")));
        String name = String.valueOf(meta.get("original_name"));
        String contentType = meta.get("content_type") != null
                ? String.valueOf(meta.get("content_type")) : MediaType.APPLICATION_OCTET_STREAM_VALUE;
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename*=UTF-8''" + URLEncoder.encode(name, StandardCharsets.UTF_8))
                .body(new FileSystemResource(path));
    }

    @GetMapping("/{id}/links")
    public List<Map<String, Object>> links(@PathVariable Long id) {
        return storage.linksOf(id);
    }

    @GetMapping("/for")
    public List<Map<String, Object>> forEntity(@RequestParam String entityType,
                                               @RequestParam Long entityId) {
        return storage.forEntity(entityType, entityId, !CurrentUser.hasRole("WORKER"));
    }

    @PostMapping("/{id}/link")
    public Map<String, String> link(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        storage.link(id, String.valueOf(body.get("entity_type")),
                ((Number) body.get("entity_id")).longValue(), (String) body.get("slot"));
        return Map.of("status", "linked");
    }

    @DeleteMapping("/{id}/link")
    public Map<String, String> unlink(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        storage.unlink(id, String.valueOf(body.get("entity_type")),
                ((Number) body.get("entity_id")).longValue());
        return Map.of("status", "unlinked");
    }
}
