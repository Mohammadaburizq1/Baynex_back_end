package com.byonix.shoplink.security;

import com.byonix.shoplink.config.AuthProperties;
import com.byonix.shoplink.domain.enums.RefreshSessionScope;
import com.byonix.shoplink.security.login.GenericAuthException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RefreshTokenCredentialResolver {
    private final RefreshTokenCookieService cookieService;
    private final AuthProperties authProperties;

    /**
     * Resolves refresh token from HttpOnly cookie (web) or JSON body (mobile/dev).
     * When {@code app.auth.refresh-token-delivery=BODY}, body token is mandatory.
     */
    public String resolve(String bodyToken, HttpServletRequest request, RefreshSessionScope scope) {
        boolean hasBody = bodyToken != null && !bodyToken.isBlank();
        var cookieToken = cookieService.readRefreshToken(request, scope);

        if (authProperties.getRefreshTokenDelivery() == AuthProperties.RefreshTokenDelivery.BODY) {
            if (!hasBody) {
                throw new GenericAuthException();
            }
            return bodyToken.trim();
        }

        if (cookieToken.isPresent()) {
            return cookieToken.get();
        }
        if (hasBody) {
            return bodyToken.trim();
        }
        throw new GenericAuthException();
    }

    /** @deprecated use {@link #resolve(String, HttpServletRequest, RefreshSessionScope)} */
    public String resolve(String bodyToken, HttpServletRequest request, boolean adminSession) {
        return resolve(bodyToken, request, adminSession ? RefreshSessionScope.ADMIN : RefreshSessionScope.MERCHANT);
    }
}
