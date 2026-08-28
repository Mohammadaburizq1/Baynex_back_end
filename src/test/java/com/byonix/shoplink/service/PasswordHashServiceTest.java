package com.byonix.shoplink.service;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;

class PasswordHashServiceTest {
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(12);
    private final PasswordHashService service = new PasswordHashService(encoder);

    @Test
    void generatedHashFitsThePasswordHashColumn() {
        String hash = service.generateUnusablePasswordHash();
        assertNotNull(hash);
        assertFalse(hash.isBlank());
        assertTrue(hash.length() <= 100, "must fit password_hash VARCHAR(100)");
    }

    @Test
    void generatedHashNeverMatchesAnyAttempt() {
        String hash = service.generateUnusablePasswordHash();
        assertFalse(encoder.matches("", hash));
        assertFalse(encoder.matches("password", hash));
        assertFalse(encoder.matches("StrongPass123!", hash));
        assertFalse(encoder.matches(hash, hash));
    }

    @Test
    void eachCallProducesADifferentHash() {
        assertNotEquals(service.generateUnusablePasswordHash(), service.generateUnusablePasswordHash());
    }
}
