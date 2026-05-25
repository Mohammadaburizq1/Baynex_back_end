package com.byonix.shoplink.service.security;

import com.byonix.shoplink.config.AuthProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Dev-only diagnostic logging. Never returns tokens to HTTP clients.
 * Enable with app.auth.expose-tokens-in-response=true on local profile only.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DevTokenLogger {
    private final AuthProperties authProperties;

    public void logEmailVerificationToken(String email, String rawToken) {
        if (authProperties.isExposeTokensInResponse()) {
            log.warn("DEV ONLY email verification token for {} — do not enable in production", email);
            log.debug("DEV verification token: {}", rawToken);
        }
    }

    public void logPasswordResetToken(String email, String rawToken) {
        if (authProperties.isExposeTokensInResponse()) {
            log.warn("DEV ONLY password reset token for {} — check Mailpit http://localhost:8025 or logs below", email);
            log.warn("DEV reset token for {}: {}", email, rawToken);
        }
    }
}
