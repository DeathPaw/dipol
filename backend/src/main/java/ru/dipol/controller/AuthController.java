package ru.dipol.controller;

import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import ru.dipol.exception.BusinessRuleException;
import ru.dipol.exception.EntityNotFoundException;
import ru.dipol.model.AppUser;
import ru.dipol.repository.AppUserRepository;
import ru.dipol.service.AuditService;
import ru.dipol.service.PermissionService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Текущий пользователь и управление пользователями. */
@RestController
@RequestMapping("/api")
public class AuthController {

    private final AppUserRepository users;
    private final PermissionService permissions;
    private final PasswordEncoder encoder;
    private final AuditService audit;

    public AuthController(AppUserRepository users, PermissionService permissions,
                          PasswordEncoder encoder, AuditService audit) {
        this.users = users;
        this.permissions = permissions;
        this.encoder = encoder;
        this.audit = audit;
    }

    @GetMapping("/me")
    public Map<String, Object> me(Authentication auth) {
        AppUser user = users.findByUsername(auth.getName())
                .orElseThrow(() -> new EntityNotFoundException("Пользователь не найден"));
        Map<String, Object> result = new HashMap<>();
        result.put("id", user.id());
        result.put("username", user.username());
        result.put("display_name", user.displayName());
        result.put("role", user.role());
        result.put("employee_id", user.employeeId());
        result.put("permissions", users.effectivePermissions(user));
        return result;
    }

    @GetMapping("/users")
    public List<Map<String, Object>> list() {
        permissions.require("USER_EDIT", "Управление пользователями требует соответствующего права");
        return users.findAll().stream().map(u -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", u.id());
            m.put("username", u.username());
            m.put("display_name", u.displayName());
            m.put("role", u.role());
            m.put("employee_id", u.employeeId());
            m.put("is_active", u.isActive());
            m.put("has_pin", u.pinCode() != null);
            return m;
        }).toList();
    }

    @GetMapping("/users/permissions")
    public Map<String, Object> permissionCatalog() {
        permissions.require("USER_EDIT", null);
        return Map.of("catalog", users.permissionCatalog());
    }

    @GetMapping("/users/{id}/permissions")
    public List<Map<String, Object>> userPermissions(@PathVariable Long id) {
        permissions.require("USER_EDIT", null);
        return users.userPermissions(id);
    }

    /** Точечная выдача или отзыв права поверх роли. */
    @PutMapping("/users/{id}/permissions/{code}")
    public Map<String, Object> setPermission(@PathVariable Long id, @PathVariable String code,
                                             @RequestBody Map<String, Object> body) {
        permissions.require("USER_EDIT", null);
        Object granted = body.get("granted");
        users.setUserPermission(id, code, granted == null ? null : Boolean.valueOf(String.valueOf(granted)));
        audit.action("USER", id, "PERMISSION", code + " = " + granted);
        return Map.of("status", "ok");
    }

    @PostMapping("/users")
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        permissions.require("USER_EDIT", null);
        String username = String.valueOf(body.get("username"));
        String password = (String) body.get("password");
        if (password == null || password.length() < 4) {
            throw new BusinessRuleException("Пароль должен быть не короче 4 символов");
        }
        if (users.findByUsername(username).isPresent()) {
            throw new ru.dipol.exception.ConflictException("Логин «" + username + "» уже занят");
        }
        AppUser u = users.create(username, encoder.encode(password),
                String.valueOf(body.get("display_name")),
                String.valueOf(body.getOrDefault("role", "OPERATOR")),
                num(body.get("employee_id")), (String) body.get("pin_code"));
        audit.created("USER", u.id());
        return Map.of("id", u.id());
    }

    @PutMapping("/users/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        permissions.require("USER_EDIT", null);
        AppUser before = users.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Пользователь", id));
        users.update(id, String.valueOf(body.get("display_name")),
                String.valueOf(body.getOrDefault("role", before.role())),
                num(body.get("employee_id")),
                !Boolean.FALSE.equals(body.get("is_active")),
                (String) body.get("pin_code"));
        audit.changed("USER", id, "role", before.role(), body.get("role"));
        audit.changed("USER", id, "is_active", before.isActive(), body.get("is_active"));
        return Map.of("status", "updated");
    }

    @PostMapping("/users/{id}/password")
    public Map<String, Object> changePassword(@PathVariable Long id, @RequestBody Map<String, String> body) {
        permissions.require("USER_EDIT", null);
        String password = body.get("password");
        if (password == null || password.length() < 4) {
            throw new BusinessRuleException("Пароль должен быть не короче 4 символов");
        }
        users.updatePassword(id, encoder.encode(password));
        audit.action("USER", id, "PASSWORD", "пароль изменён");
        return Map.of("status", "ok");
    }

    private static Long num(Object v) {
        return v == null ? null : ((Number) v).longValue();
    }
}
