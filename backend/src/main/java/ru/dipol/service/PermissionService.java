package ru.dipol.service;

import org.springframework.stereotype.Service;
import ru.dipol.audit.CurrentUser;
import ru.dipol.exception.ForbiddenException;
import ru.dipol.model.AppUser;
import ru.dipol.repository.AppUserRepository;

import java.util.Set;

/**
 * Точечные права поверх роли. Роль даёт базовый набор, отдельные права
 * (снабжение, расчёты, закрытие периода) выдаются пользователю индивидуально —
 * так не приходится плодить роли под каждую комбинацию (US-N1).
 */
@Service
public class PermissionService {

    private final AppUserRepository users;

    public PermissionService(AppUserRepository users) {
        this.users = users;
    }

    public Set<String> current() {
        String username = CurrentUser.nameOrNull();
        if (username == null) return Set.of();
        AppUser user = users.findByUsername(username).orElse(null);
        if (user == null) return Set.of();
        return users.effectivePermissions(user);
    }

    public boolean has(String permission) {
        return current().contains(permission);
    }

    public void require(String permission, String message) {
        if (!has(permission)) {
            throw new ForbiddenException(message != null ? message
                    : "Недостаточно прав: требуется «" + permission + "»");
        }
    }
}
