package com.byonix.shoplink.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RateLimitFilter extends OncePerRequestFilter {
    private static final Set<String> DASHBOARD_MUTATING = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Limit limit = limitFor(request);
        if (limit == null) {
            chain.doFilter(request, response);
            return;
        }
        String key = limit.name + ":" + clientIp(request);
        long now = Instant.now().getEpochSecond();
        Window window = windows.compute(key, (k, old) -> {
            if (old == null || now >= old.resetAt) {
                return new Window(now + limit.windowSeconds, 1);
            }
            old.count++;
            return old;
        });
        if (window.count > limit.maxRequests) {
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write("{\"success\":false,\"message\":\"Too many requests\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private Limit limitFor(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();

        if ("GET".equals(method) && path.startsWith("/api/public/")) {
            return new Limit("public-read", 600, 60);
        }
        if ("GET".equals(method) && path.startsWith("/api/dashboard/")) {
            return new Limit("dashboard-read", 300, 60);
        }
        if (path.startsWith("/api/dashboard/") && DASHBOARD_MUTATING.contains(method)) {
            return new Limit("dashboard-write", 120, 60);
        }

        if (!"POST".equals(method)) {
            return null;
        }
        if (path.equals("/api/auth/login")) return new Limit("login", 10, 60);
        if (path.equals("/api/admin/auth/login")) return new Limit("admin-login", 5, 60);
        if (path.equals("/api/auth/register")) return new Limit("register", 5, 300);
        if (path.equals("/api/auth/refresh")) return new Limit("refresh", 30, 60);
        if (path.equals("/api/auth/logout")) return new Limit("logout", 40, 60);
        if (path.equals("/api/auth/verify-email")) return new Limit("verify-email", 30, 3600);
        if (path.equals("/api/auth/verify-email/resend")) return new Limit("verify-resend", 10, 3600);
        if (path.equals("/api/auth/forgot-password")) return new Limit("forgot-password", 5, 3600);
        if (path.equals("/api/auth/reset-password")) return new Limit("reset-password", 15, 3600);
        if (path.matches("^/api/public/stores/[^/]+/orders$")) {
            return new Limit("public-order", 20, 300);
        }
        if (path.matches("^/api/public/stores/[^/]+/orders/lookup$")) {
            return new Limit("public-order-lookup", 20, 300);
        }
        return null;
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private record Limit(String name, int maxRequests, long windowSeconds) {}

    private static final class Window {
        private final long resetAt;
        private int count;

        private Window(long resetAt, int count) {
            this.resetAt = resetAt;
            this.count = count;
        }
    }

    public static class RateLimitExceededException extends RuntimeException {}
}
