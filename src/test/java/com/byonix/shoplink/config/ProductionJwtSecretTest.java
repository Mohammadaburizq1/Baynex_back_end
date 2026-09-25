package com.byonix.shoplink.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProductionJwtSecretTest {
    @Test void rejectsMissingShortRepeatedAndKnownDefaults() {
        for (String secret : new String[]{"", "short", "a".repeat(80), "change-me-".repeat(10),
                "test-secret-0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"}) {
            assertThrows(IllegalStateException.class, () -> ProductionSecurityValidator.validateJwtSecret(secret));
        }
    }
    @Test void acceptsGeneratedSecretWithoutIncludingItInErrors() {
        byte[] bytes = new byte[48];
        new java.security.SecureRandom().nextBytes(bytes);
        assertDoesNotThrow(() -> ProductionSecurityValidator.validateJwtSecret(java.util.Base64.getEncoder().encodeToString(bytes)));
    }
}
