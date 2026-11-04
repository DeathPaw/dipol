package ru.dipol.controller;

import org.springframework.web.bind.annotation.*;
import ru.dipol.audit.CurrentUser;
import ru.dipol.model.AuditLogEntry;
import ru.dipol.model.ErrorLogEntry;
import ru.dipol.repository.AuditLogRepository;
import ru.dipol.repository.ErrorLogRepository;

import java.util.List;
import java.util.Map;

/** Журналы: действия пользователей и ошибки приложения. */
@RestController
@RequestMapping("/api")
public class LogController {

    private final AuditLogRepository auditLog;
    private final ErrorLogRepository errorLog;

    public LogController(AuditLogRepository auditLog, ErrorLogRepository errorLog) {
        this.auditLog = auditLog;
        this.errorLog = errorLog;
    }

    @GetMapping("/audit-log")
    public List<AuditLogEntry> audit(@RequestParam(required = false) String entityType,
                                     @RequestParam(required = false) Long entityId,
                                     @RequestParam(required = false) String actor,
                                     @RequestParam(defaultValue = "300") int limit) {
        return auditLog.find(entityType, entityId, actor, limit);
    }

    @GetMapping("/audit-log/entity-types")
    public List<String> entityTypes() {
        return auditLog.entityTypes();
    }

    @GetMapping("/error-log")
    public List<ErrorLogEntry> errors(@RequestParam(defaultValue = "200") int limit) {
        return errorLog.findRecent(limit);
    }

    /** Ошибка интерфейса — чтобы падение фронта не терялось после перезагрузки. */
    @PostMapping("/error-log")
    public Map<String, String> logFrontendError(@RequestBody Map<String, String> body) {
        errorLog.save(body.getOrDefault("error_type", "FRONTEND_ERROR"),
                body.getOrDefault("message", ""), body.get("request_path"), CurrentUser.nameOrNull());
        return Map.of("status", "logged");
    }

    @DeleteMapping("/error-log")
    public Map<String, String> clear() {
        errorLog.clear();
        return Map.of("status", "cleared");
    }
}
