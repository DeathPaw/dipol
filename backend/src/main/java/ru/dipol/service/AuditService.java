package ru.dipol.service;

import org.springframework.stereotype.Service;
import ru.dipol.audit.CurrentUser;
import ru.dipol.repository.AuditLogRepository;

import java.util.Map;
import java.util.Objects;

/**
 * Тонкая обёртка над журналом действий: подставляет текущего пользователя и
 * не пишет запись, если значение фактически не изменилось.
 */
@Service
public class AuditService {

    private final AuditLogRepository repo;

    public AuditService(AuditLogRepository repo) {
        this.repo = repo;
    }

    public void created(String entityType, Long id) {
        repo.log(entityType, id, "CREATE", null, null, null, null, CurrentUser.name());
    }

    public void deleted(String entityType, Long id, String comment) {
        repo.log(entityType, id, "DELETE", null, null, null, comment, CurrentUser.name());
    }

    public void changed(String entityType, Long id, String field, Object oldValue, Object newValue) {
        if (Objects.equals(str(oldValue), str(newValue))) return;
        repo.log(entityType, id, "UPDATE", field, str(oldValue), str(newValue), null, CurrentUser.name());
    }

    /** Сравнивает два снимка полей и пишет только отличия. */
    public void diff(String entityType, Long id, Map<String, Object> before, Map<String, Object> after) {
        for (var e : after.entrySet()) {
            changed(entityType, id, e.getKey(), before.get(e.getKey()), e.getValue());
        }
    }

    public void status(String entityType, Long id, String from, String to, String reason) {
        repo.log(entityType, id, "STATUS", "status", from, to, reason, CurrentUser.name());
    }

    public void action(String entityType, Long id, String action, String comment) {
        repo.log(entityType, id, action, null, null, null, comment, CurrentUser.name());
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
