package com.byonix.shoplink.config;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ProductionCorsTest {
    @Test void exactHttpsAllowListOnly() {
        assertDoesNotThrow(() -> ProductionSecurityValidator.validateOrigins("https://shop.example.org,https://admin.example.org"));
        for (String value : new String[]{"", "*", "https://*.example.org", "http://example.org", "https://localhost", "https://example.org/path", "https://user@example.org"})
            assertThrows(IllegalStateException.class, () -> ProductionSecurityValidator.validateOrigins(value));
    }
}
