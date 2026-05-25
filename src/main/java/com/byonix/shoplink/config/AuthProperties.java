package com.byonix.shoplink.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.auth")
public class AuthProperties {
    /**
     * Never enable in production. When true (dev profile only), verification/reset tokens may be logged server-side.
     */
    private boolean exposeTokensInResponse = false;

    /**
     * body = refresh token in JSON (mobile/dev). cookie = HttpOnly Secure cookie (Flutter Web production).
     * cookie_or_body = accept cookie or body on refresh/logout.
     */
    private RefreshTokenDelivery refreshTokenDelivery = RefreshTokenDelivery.BODY;

    private long mfaChallengeExpirationMinutes = 5;

    private boolean requireSuperAdminMfa = true;

    public enum RefreshTokenDelivery {
        BODY, COOKIE, COOKIE_OR_BODY
    }
}
