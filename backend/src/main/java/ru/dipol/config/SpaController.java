package ru.dipol.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Бэк раздаёт собранный фронт из classpath:/static/ и форвардит SPA-маршруты
 * на index.html, чтобы React Router работал при F5 на любой странице.
 * API живёт на /api/**, статика (.js/.css) раздаётся Spring'ом сама.
 */
@Controller
public class SpaController {

    @GetMapping({
            "/", "/login", "/dashboard",
            "/requests", "/requests/{id:[0-9]+}",
            "/trips", "/trips/{id:[0-9]+}",
            "/equipment-shifts",
            "/buildings", "/buildings/{id:[0-9]+}",
            "/zheu", "/clients", "/clients/{id:[0-9]+}",
            "/projects", "/projects/{id:[0-9]+}", "/project-configs", "/project-configs/{id:[0-9]+}",
            "/rk3", "/rk3/{id:[0-9]+}",
            "/materials", "/purchases", "/stock",
            "/payroll", "/compensations", "/bridge",
            "/finance", "/cash-accounts",
            "/documents", "/reports", "/period-closing",
            "/safety", "/permits",
            "/transport", "/equipment",
            "/analytics", "/data-quality",
            "/references", "/employees", "/users", "/audit-log", "/error-log", "/import",
            "/worker-login", "/worker", "/worker/task/{id:[0-9]+}"
    })
    public String spa() {
        return "forward:/index.html";
    }
}
