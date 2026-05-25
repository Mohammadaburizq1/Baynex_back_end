package com.byonix.shoplink.service.security;

import com.byonix.shoplink.api.dto.AuthDtos;
import com.byonix.shoplink.config.AuthProperties;
import com.byonix.shoplink.domain.entity.User;
import com.byonix.shoplink.domain.enums.Role;
import com.byonix.shoplink.service.AuthService;
import com.byonix.shoplink.service.MapperService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductionTokenExposureTest {
    @Mock AuthProperties authProperties;
    @Mock MapperService mapper;
    @InjectMocks AuthResponseFactory factory;

    @Test
    void authResponseOmitsRefreshTokenInCookieMode() {
        User user = new User();
        user.setRole(Role.MERCHANT_OWNER);
        when(authProperties.getRefreshTokenDelivery()).thenReturn(AuthProperties.RefreshTokenDelivery.COOKIE);
        when(mapper.user(user)).thenReturn(new AuthDtos.UserResponse(
                user.getId(), "n", "e@e.com", null, Role.MERCHANT_OWNER, true, 0, false));

        AuthDtos.AuthResponse response = factory.authenticated(user, "access", "refresh-raw", null, false);
        assertNull(response.refreshToken());
    }

    @Test
    void actionResponseIsMessageOnly() {
        AuthDtos.AuthActionResponse response = factory.actionMessage("If the email exists, instructions were sent.");
        org.junit.jupiter.api.Assertions.assertEquals("If the email exists, instructions were sent.", response.message());
    }
}
