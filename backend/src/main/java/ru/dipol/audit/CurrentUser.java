package ru.dipol.audit;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Имя текущего пользователя для полей actor/created_by. Отдельный хелпер, а не
 * параметр каждого метода: иначе Authentication пришлось бы тащить через
 * контроллер → сервис → репозиторий во всех модулях.
 */
public final class CurrentUser {

    private CurrentUser() {}

    /** «admin» либо «system», если запрос идёт не от пользователя (импорт, планировщик). */
    public static String name() {
        String n = nameOrNull();
        return n != null ? n : "system";
    }

    public static String nameOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return null;
        String name = auth.getName();
        return "anonymousUser".equals(name) ? null : name;
    }

    public static boolean hasRole(String role) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        return auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_" + role));
    }
}
