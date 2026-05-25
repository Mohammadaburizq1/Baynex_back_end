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
public class ProductionSecurityValidator implements ApplicationRunner {
    private final Environment environment;
    private final AuthProperties authProperties;
    private final LoginSecurityProperties loginSecurityProperties;

    @org.springframework.beans.factory.annotation.Value("${app.jwt.refresh-expiration-days:7}")
    private long merchantRefreshExpirationDays;

    @org.springframework.beans.factory.annotation.Value("${app.jwt.admin-refresh-expiration-days:1}")
    private long adminRefreshExpirationDays;

    @Override
    public void run(ApplicationArguments args) {
        if (!isProductionProfile()) {
            return;
        }
        log.info("Validating production security configuration");
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
        if (authProperties.getRefreshTokenDelivery() == AuthProperties.RefreshTokenDelivery.BODY) {
            log.warn("Production is using refresh-token-delivery=BODY; prefer COOKIE for Flutter Web");
        }
    }

    private boolean isProductionProfile() {
        return Arrays.stream(environment.getActiveProfiles()).anyMatch("prod"::equalsIgnoreCase);
    }
}
