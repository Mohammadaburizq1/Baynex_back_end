package com.byonix.shoplink.security;

import com.byonix.shoplink.config.AuthProperties;
import com.byonix.shoplink.security.login.GenericAuthException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefreshTokenCredentialResolverTest {
    @Mock RefreshTokenCookieService cookieService;
    @Mock AuthProperties authProperties;
    @Mock HttpServletRequest request;
    @InjectMocks RefreshTokenCredentialResolver resolver;

    @Test
    void refreshWorksFromBodyWithoutAccessToken() {
        when(authProperties.getRefreshTokenDelivery()).thenReturn(AuthProperties.RefreshTokenDelivery.BODY);
        String token = resolver.resolve("body-refresh-token", request, false);
        assertEquals("body-refresh-token", token);
    }

    @Test
    void refreshWorksFromCookieWhenCookieOrBodyMode() {
        when(authProperties.getRefreshTokenDelivery()).thenReturn(AuthProperties.RefreshTokenDelivery.COOKIE_OR_BODY);
        when(cookieService.readRefreshToken(request, false)).thenReturn(Optional.of("cookie-token"));
        String token = resolver.resolve(null, request, false);
        assertEquals("cookie-token", token);
    }

    @Test
    void failsWhenNoCredentialInBodyMode() {
        when(authProperties.getRefreshTokenDelivery()).thenReturn(AuthProperties.RefreshTokenDelivery.BODY);
        assertThrows(GenericAuthException.class, () -> resolver.resolve(null, request, false));
    }
}
