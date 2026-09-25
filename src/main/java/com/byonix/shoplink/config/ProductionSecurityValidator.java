package com.byonix.shoplink.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Fail-fast checks so production cannot start with unsafe auth settings.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductionSecurityValidator implements ApplicationRunner, org.springframework.beans.factory.InitializingBean {
    private final Environment environment;
    private final AuthProperties authProperties;
    private final LoginSecurityProperties loginSecurityProperties;

    @org.springframework.beans.factory.annotation.Value("${app.jwt.refresh-expiration-days:7}")
    private long merchantRefreshExpirationDays;

    @org.springframework.beans.factory.annotation.Value("${app.jwt.admin-refresh-expiration-days:1}")
    private long adminRefreshExpirationDays;

    @Override
    public void afterPropertiesSet() { run(null); }

    @Override
    public void run(ApplicationArguments args) {
        if (!isProductionProfile()) {
            return;
        }
        log.info("Validating production security configuration");
        if (Arrays.stream(environment.getActiveProfiles()).anyMatch(p -> p.equalsIgnoreCase("local") || p.equalsIgnoreCase("test") || p.equalsIgnoreCase("dev"))) {
            throw new IllegalStateException("Production cannot be combined with local, dev or test profiles");
        }
        validateJwtSecret(environment.getProperty("app.jwt.secret", ""));
        if (!environment.getProperty("spring.datasource.url", "").startsWith("jdbc:postgresql:")
                || environment.getProperty("spring.datasource.username", "").isBlank()
                || environment.getProperty("spring.datasource.password", "").isBlank()
                || !environment.getProperty("spring.flyway.enabled", Boolean.class, true)
                || !"validate".equals(environment.getProperty("spring.jpa.hibernate.ddl-auto", "validate"))) {
            throw new IllegalStateException("Production requires explicit PostgreSQL credentials, Flyway migrations and schema validation");
        }
        if (environment.getProperty("app.mail.enabled", Boolean.class, false)) {
            if (environment.getProperty("spring.mail.host", "").isBlank()) throw new IllegalStateException("Enabled production email requires SMTP configuration");
            if (!environment.getProperty("spring.mail.properties.mail.smtp.starttls.required", Boolean.class, true)
                    && !environment.getProperty("spring.mail.properties.mail.smtp.ssl.enable", Boolean.class, false)) {
                throw new IllegalStateException("Production SMTP requires TLS");
            }
        } else if (!environment.getProperty("app.mail.disabled-acknowledged", Boolean.class, false)) {
            throw new IllegalStateException("Disabled production email must be explicitly acknowledged; recovery/invitations will not be delivered");
        }
        validateOrigins(environment.getProperty("app.mail.frontend-base-url", ""));
        validateOrigins(environment.getProperty("app.media.public-base-url", ""));
        if (!environment.getProperty("app.media.persistent-storage", Boolean.class, false)
                || !java.nio.file.Path.of(environment.getProperty("app.media.storage-dir", ".")).isAbsolute()) {
            throw new IllegalStateException("Production local media requires an absolute persistent-volume path and explicit acknowledgement");
        }
        validateOrigins(environment.getProperty("app.cors.allowed-origins", ""));
        if (!environment.getProperty("app.cors.allowed-origin-patterns", "").isBlank()
                || !environment.getProperty("app.auth.refresh-cookie-secure", Boolean.class, true)
                || !"Strict".equalsIgnoreCase(environment.getProperty("app.auth.refresh-cookie-same-site", "Strict"))
                || !environment.getProperty("app.security.hsts.enabled", Boolean.class, false)
                || environment.getProperty("app.security.hsts.max-age-seconds", Long.class, 31536000L) < 1) {
            throw new IllegalStateException("Production requires exact CORS origins, Secure SameSite=Strict cookies, and HSTS");
        }
        if (!"memory".equals(environment.getProperty("app.security.rate-limit.backend", "memory"))
                || !environment.getProperty("app.security.rate-limit.single-instance", Boolean.class, false)) {
            throw new IllegalStateException("Production memory rate limiting requires explicit single-instance acknowledgement; distributed backend is not implemented");
        }
        if (environment.getProperty("app.swagger.enabled", Boolean.class, false)
                || environment.getProperty("springdoc.api-docs.enabled", Boolean.class, false)
                || environment.getProperty("springdoc.swagger-ui.enabled", Boolean.class, false)
                || !"health".equals(environment.getProperty("management.endpoints.web.exposure.include", "health"))) {
            throw new IllegalStateException("Production documentation must be disabled and actuator exposure restricted to health");
        }
        if (!"never".equals(environment.getProperty("management.endpoint.health.show-details", "never"))
                || !"never".equals(environment.getProperty("server.error.include-stacktrace", "never"))
                || !"never".equals(environment.getProperty("server.error.include-message", "never"))
                || environment.getProperty("spring.mvc.log-request-details", Boolean.class, false)) {
            throw new IllegalStateException("Production diagnostics must not expose request or exception details");
        }
        long accessMinutes = environment.getProperty("app.jwt.access-expiration-minutes", Long.class, 15L);
        if (accessMinutes < 1 || accessMinutes > 15 || merchantRefreshExpirationDays < 1
                || merchantRefreshExpirationDays > 30 || adminRefreshExpirationDays < 1) {
            throw new IllegalStateException("Invalid production token lifetime");
        }
        if (authProperties.isExposeTokensInResponse()) {
            throw new IllegalStateException(
                    "FATAL: app.auth.expose-tokens-in-response must be false when spring profile 'prod' is active");
        }
        if (loginSecurityProperties.isMfaDevBypass()) {
            throw new IllegalStateException(
                    "FATAL: app.security.login.mfa-dev-bypass must be false when spring profile 'prod' is active");
        }
        if (adminRefreshExpirationDays >= merchantRefreshExpirationDays) {
            throw new IllegalStateException(
                    "FATAL: app.jwt.admin-refresh-expiration-days must be less than app.jwt.refresh-expiration-days in production");
        }
        if (authProperties.getRefreshTokenDelivery() != AuthProperties.RefreshTokenDelivery.COOKIE
                || !authProperties.isRequireSuperAdminMfa()) {
            throw new IllegalStateException("Production requires cookie-only refresh tokens and super-admin MFA");
        }
    }

    private boolean isProductionProfile() {
        return Arrays.stream(environment.getActiveProfiles()).anyMatch("prod"::equalsIgnoreCase);
    }

    static void validateJwtSecret(String secret) {
        String lower = secret == null ? "" : secret.toLowerCase(java.util.Locale.ROOT);
        if (secret == null || secret.length() < 64 || secret.chars().distinct().count() < 16
                || lower.contains("change-me") || lower.contains("changeme")
                || lower.contains("password") || lower.contains("test-secret")
                || lower.contains("example") || lower.contains("placeholder")) {
            throw new IllegalStateException("Production JWT secret must be randomly generated, at least 64 characters, and not a default");
        }
    }

    static void validateOrigins(String origins) {
        if (origins == null || origins.isBlank()) throw new IllegalStateException("Production CORS allow-list is required");
        for (String origin : origins.split(",", -1)) {
            try {
                var uri = java.net.URI.create(origin.trim());
                if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                        || uri.getQuery() != null || uri.getFragment() != null || !uri.getPath().isEmpty()
                        || "localhost".equalsIgnoreCase(uri.getHost()) || uri.getHost().equals("127.0.0.1")) {
                    throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException ex) {
                throw new IllegalStateException("Production CORS origins must be exact HTTPS origins without paths");
            }
        }
    }
}
