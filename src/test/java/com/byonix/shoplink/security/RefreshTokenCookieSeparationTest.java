package com.byonix.shoplink.security;

import com.byonix.shoplink.config.AuthProperties;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
@ExtendWith(MockitoExtension.class)
class RefreshTokenCookieSeparationTest {
    @Mock AuthProperties authProperties;
    @InjectMocks RefreshTokenCookieService cookieService;

    @Test
    void merchantEndpointReadsOnlyMerchantCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(
                new Cookie(RefreshTokenCookieService.REFRESH_COOKIE, "merchant-token"),
                new Cookie(RefreshTokenCookieService.ADMIN_REFRESH_COOKIE, "admin-token"));

        Optional<String> merchant = cookieService.readRefreshToken(request, false);
        Optional<String> admin = cookieService.readRefreshToken(request, true);

        assertTrue(merchant.isPresent());
        assertEquals("merchant-token", merchant.get());
        assertTrue(admin.isPresent());
        assertEquals("admin-token", admin.get());
    }

    @Test
    void merchantRefreshDoesNotReadAdminCookieWhenMerchantCookieAbsent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(RefreshTokenCookieService.ADMIN_REFRESH_COOKIE, "admin-only"));

        assertTrue(cookieService.readRefreshToken(request, false).isEmpty());
    }

    @Test
    void adminRefreshDoesNotReadMerchantCookieWhenAdminCookieAbsent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(RefreshTokenCookieService.REFRESH_COOKIE, "merchant-only"));

        assertTrue(cookieService.readRefreshToken(request, true).isEmpty());
    }
}
