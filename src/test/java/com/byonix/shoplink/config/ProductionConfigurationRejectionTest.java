package com.byonix.shoplink.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

class ProductionConfigurationRejectionTest {
    private MockEnvironment safeEnvironment() {
        var env = new MockEnvironment(); env.setActiveProfiles("prod");
        byte[] bytes = new byte[48]; new java.security.SecureRandom().nextBytes(bytes);
        return env.withProperty("app.jwt.secret", java.util.Base64.getEncoder().encodeToString(bytes))
                .withProperty("spring.datasource.url", "jdbc:postgresql://localhost/disposable")
                .withProperty("spring.datasource.username", "test").withProperty("spring.datasource.password", "test")
                .withProperty("app.mail.disabled-acknowledged", "true")
                .withProperty("app.mail.frontend-base-url", "https://store.example.org")
                .withProperty("app.media.public-base-url", "https://media.example.org")
                .withProperty("app.media.storage-dir", System.getProperty("java.io.tmpdir"))
                .withProperty("app.media.persistent-storage", "true")
                .withProperty("app.cors.allowed-origins", "https://store.example.org")
                .withProperty("app.security.hsts.enabled", "true")
                .withProperty("app.security.rate-limit.single-instance", "true");
    }
    private ProductionSecurityValidator validator(MockEnvironment env) {
        var auth = new AuthProperties(); auth.setRefreshTokenDelivery(AuthProperties.RefreshTokenDelivery.COOKIE);
        var validator = new ProductionSecurityValidator(env, auth, new LoginSecurityProperties());
        ReflectionTestUtils.setField(validator, "merchantRefreshExpirationDays", 7L);
        ReflectionTestUtils.setField(validator, "adminRefreshExpirationDays", 1L);
        return validator;
    }
    @Test void validConfigurationPasses() { assertDoesNotThrow(() -> validator(safeEnvironment()).afterPropertiesSet()); }
    @Test void eachUnsafeOverrideIsRejected() {
        String[][] overrides = {
            {"app.jwt.secret", "change-me-".repeat(10)}, {"app.swagger.enabled", "true"},
            {"springdoc.api-docs.enabled", "true"}, {"management.endpoints.web.exposure.include", "*"},
            {"spring.datasource.url", "jdbc:h2:mem:unsafe"}, {"spring.datasource.password", ""},
            {"spring.flyway.enabled", "false"}, {"spring.jpa.hibernate.ddl-auto", "update"},
            {"app.mail.disabled-acknowledged", "false"}, {"app.media.persistent-storage", "false"},
            {"app.cors.allowed-origins", "*"}, {"app.cors.allowed-origin-patterns", "https://*"},
            {"app.auth.refresh-cookie-secure", "false"}, {"app.auth.refresh-cookie-same-site", "None"},
            {"app.security.hsts.enabled", "false"}, {"app.security.rate-limit.backend", "redis"},
            {"app.security.rate-limit.single-instance", "false"}, {"app.jwt.access-expiration-minutes", "120"}
        };
        for (String[] override : overrides) assertThrows(IllegalStateException.class,
                () -> validator(safeEnvironment().withProperty(override[0], override[1])).afterPropertiesSet(), override[0]);
    }
}
