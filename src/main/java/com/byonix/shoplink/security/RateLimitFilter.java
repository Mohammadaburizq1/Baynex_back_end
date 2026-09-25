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

    private final com.byonix.shoplink.security.ratelimit.RateLimiter limiter;
    public RateLimitFilter() { this(new com.byonix.shoplink.security.ratelimit.InMemoryRateLimiter()); }
    @org.springframework.beans.factory.annotation.Autowired
    public RateLimitFilter(com.byonix.shoplink.security.ratelimit.RateLimiter limiter) { this.limiter = limiter; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Limit limit = limitFor(request);
        if (limit == null) {
            chain.doFilter(request, response);
            return;
        }
        String key = limit.name + ":" + clientIp(request);
        if (!limiter.tryConsume("route:" + key, limit.maxRequests, limit.windowSeconds)) {
            response.setStatus(429);
            response.setHeader("Retry-After", Long.toString(limit.windowSeconds));
            response.setContentType("application/json");
            response.getWriter().write("{\"success\":false,\"message\":\"Too many requests\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private Limit limitFor(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getServletPath();
        if (path == null || path.isEmpty()) path = request.getRequestURI();

        if (("GET".equals(method) || "HEAD".equals(method))
                && (path.startsWith("/api/auth/") || path.startsWith("/api/public/auth/") || path.startsWith("/api/admin/auth/"))) {
            return new Limit("auth-read", 20, 60);
        }
        if (("GET".equals(method) || "HEAD".equals(method)) && path.startsWith("/api/public/")) {
            return new Limit("public-read", 600, 60);
        }
        if ("GET".equals(method) && path.startsWith("/api/pos/")) {
            return new Limit("pos-read", 120, 60);
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
        if (path.equals("/api/pos/activate")) return new Limit("pos-activate", 10, 300);
        if (path.equals("/api/auth/login")) return new Limit("login", 10, 60);
        if (path.equals("/api/auth/login-phone")) return new Limit("login-phone", 10, 60);
        if (path.equals("/api/auth/google")) return new Limit("google-login", 10, 60);
        if (path.equals("/api/admin/auth/login")) return new Limit("admin-login", 5, 60);
        if (path.equals("/api/auth/register")) return new Limit("register", 5, 300);
        if (path.equals("/api/auth/register-phone")) return new Limit("register-phone", 5, 300);
        if (path.equals("/api/auth/refresh")) return new Limit("refresh", 30, 60);
        if (path.equals("/api/auth/logout")) return new Limit("logout", 40, 60);
        if (path.equals("/api/auth/verify-email")) return new Limit("verify-email", 30, 3600);
        if (path.equals("/api/auth/verify-email/resend")) return new Limit("verify-resend", 10, 3600);
        if (path.equals("/api/auth/forgot-password")) return new Limit("forgot-password", 5, 3600);
        if (path.equals("/api/auth/reset-password")) return new Limit("reset-password", 15, 3600);
        if (path.equals("/api/auth/verify-phone")) return new Limit("verify-phone", 10, 300);
        if (path.equals("/api/auth/verify-phone/resend")) return new Limit("verify-phone-resend", 10, 3600);
        if (path.equals("/api/auth/forgot-password-phone")) return new Limit("forgot-password-phone", 5, 3600);
        if (path.equals("/api/auth/reset-password-phone")) return new Limit("reset-password-phone", 15, 3600);
        if (path.equals("/api/public/auth/register")) return new Limit("customer-register", 5, 300);
        if (path.equals("/api/public/auth/login")) return new Limit("customer-login", 10, 60);
        if (path.equals("/api/public/auth/refresh")) return new Limit("customer-refresh", 30, 60);
        if (path.equals("/api/public/auth/logout")) return new Limit("customer-logout", 40, 60);
        if (path.equals("/api/public/auth/forgot-password")) return new Limit("customer-forgot-password", 5, 3600);
        if (path.equals("/api/public/auth/reset-password")) return new Limit("customer-reset-password", 15, 3600);
        if (path.matches("^/api/public/stores/[^/]+/orders$")) {
            return new Limit("public-order", 20, 300);
        }
        if (path.matches("^/api/public/stores/[^/]+/orders/lookup$")) {
            return new Limit("public-order-lookup", 20, 300);
        }
        if (path.startsWith("/api/auth/") || path.startsWith("/api/admin/auth/") || path.startsWith("/api/public/auth/")) {
            return new Limit("auth-other", 10, 300);
        }
        if (path.startsWith("/api/public/")) return new Limit("public-write", 30, 60);
        return null;
    }

    private String clientIp(HttpServletRequest request) {
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
