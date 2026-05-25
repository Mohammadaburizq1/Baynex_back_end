package com.byonix.shoplink.security;

import com.byonix.shoplink.config.AuthProperties;
import com.byonix.shoplink.domain.enums.RefreshSessionScope;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

/**
 * HttpOnly Secure cookie transport for refresh tokens (Flutter Web production).
 * Mobile clients can continue using JSON body when app.auth.refresh-token-delivery=body.
 */
@Service
@RequiredArgsConstructor
public class RefreshTokenCookieService {
    public static final String REFRESH_COOKIE = "shoplink_refresh";
    public static final String ADMIN_REFRESH_COOKIE = "shoplink_admin_refresh";
    public static final String CUSTOMER_REFRESH_COOKIE = "shoplink_customer_refresh";

    private final AuthProperties authProperties;

    @Value("${app.auth.refresh-cookie-secure:true}")
    private boolean secureCookie;

    @Value("${app.auth.refresh-cookie-same-site:Strict}")
    private String sameSite;

    @Value("${app.jwt.refresh-expiration-days:7}")
    private long merchantRefreshExpirationDays;

    @Value("${app.jwt.admin-refresh-expiration-days:1}")
    private long adminRefreshExpirationDays;

    @Value("${app.jwt.customer-refresh-expiration-days:30}")
    private long customerRefreshExpirationDays;

    public boolean useCookieDelivery() {
        return authProperties.getRefreshTokenDelivery() == AuthProperties.RefreshTokenDelivery.COOKIE
                || authProperties.getRefreshTokenDelivery() == AuthProperties.RefreshTokenDelivery.COOKIE_OR_BODY;
    }

    public void writeRefreshCookie(HttpServletResponse response, String rawToken, RefreshSessionScope scope) {
        if (!useCookieDelivery()) {
            return;
        }
        long days = refreshDaysFor(scope);
        ResponseCookie cookie = baseCookie(cookieName(scope), rawToken)
                .maxAge(Duration.ofDays(days))
                .build();
        response.addHeader("Set-Cookie", cookie.toString());
    }

    public void clearRefreshCookie(HttpServletResponse response, RefreshSessionScope scope) {
        if (!useCookieDelivery()) {
            return;
        }
        ResponseCookie cookie = baseCookie(cookieName(scope), "")
                .maxAge(Duration.ZERO)
                .build();
        response.addHeader("Set-Cookie", cookie.toString());
    }

    public Optional<String> readRefreshToken(HttpServletRequest request, RefreshSessionScope scope) {
        String name = cookieName(scope);
        if (request.getCookies() == null) {
            return Optional.empty();
        }
        return Arrays.stream(request.getCookies())
                .filter(c -> name.equals(c.getName()))
                .map(Cookie::getValue)
                .filter(v -> v != null && !v.isBlank())
                .findFirst();
    }

    /** @deprecated use {@link #writeRefreshCookie(HttpServletResponse, String, RefreshSessionScope)} */
    public void writeRefreshCookie(HttpServletResponse response, String rawToken, boolean admin) {
        writeRefreshCookie(response, rawToken, admin ? RefreshSessionScope.ADMIN : RefreshSessionScope.MERCHANT);
    }

    /** @deprecated use {@link #clearRefreshCookie(HttpServletResponse, RefreshSessionScope)} */
    public void clearRefreshCookie(HttpServletResponse response, boolean admin) {
        clearRefreshCookie(response, admin ? RefreshSessionScope.ADMIN : RefreshSessionScope.MERCHANT);
    }

    /** @deprecated use {@link #readRefreshToken(HttpServletRequest, RefreshSessionScope)} */
    public Optional<String> readRefreshToken(HttpServletRequest request, boolean admin) {
        return readRefreshToken(request, admin ? RefreshSessionScope.ADMIN : RefreshSessionScope.MERCHANT);
    }

    private String cookieName(RefreshSessionScope scope) {
        return switch (scope) {
            case ADMIN -> ADMIN_REFRESH_COOKIE;
            case CUSTOMER -> CUSTOMER_REFRESH_COOKIE;
            case MERCHANT -> REFRESH_COOKIE;
        };
    }

    private long refreshDaysFor(RefreshSessionScope scope) {
        return switch (scope) {
            case ADMIN -> adminRefreshExpirationDays;
            case CUSTOMER -> customerRefreshExpirationDays;
            case MERCHANT -> merchantRefreshExpirationDays;
        };
    }

    private ResponseCookie.ResponseCookieBuilder baseCookie(String name, String value) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(secureCookie)
                .path("/")
                .sameSite(sameSite);
    }
}
