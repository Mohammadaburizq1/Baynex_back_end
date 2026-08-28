package com.byonix.shoplink.service;

import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Produces password_hash values for accounts that never set a real password
 * (e.g. sign-up via an external identity provider such as Google). The column
 * stays NOT NULL; this generates a normal-shaped BCrypt hash of a random secret
 * nobody knows, so PasswordEncoder.matches() can never succeed against it.
 */
@Service
@RequiredArgsConstructor
public class PasswordHashService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int SECRET_BYTES = 32;

    private final PasswordEncoder passwordEncoder;

    public String generateUnusablePasswordHash() {
        byte[] bytes = new byte[SECRET_BYTES];
        RANDOM.nextBytes(bytes);
        String randomSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return passwordEncoder.encode(randomSecret);
    }
}
