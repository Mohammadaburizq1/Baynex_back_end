package com.byonix.shoplink.api;

import com.byonix.shoplink.api.dto.AuthDtos;
import com.byonix.shoplink.common.ApiResponse;
import com.byonix.shoplink.security.login.SecurityMessages;
import com.byonix.shoplink.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuthLogoutResponseTest {
    @Mock AuthService authService;
    @Mock HttpServletRequest http;
    @Mock HttpServletResponse response;
    @InjectMocks AuthController controller;

    @Test
    void logoutReturnsGenericMessageWhenTokenMissing() {
        ApiResponse<AuthDtos.MessageResponse> result = controller.logout(new AuthDtos.LogoutRequest(null), http, response);
        assertEquals(SecurityMessages.GENERIC_LOGOUT_MESSAGE, result.data().message());
        verify(authService).logout(any(), any(), any());
    }

    @Test
    void logoutWithNullBodyReturnsGenericMessage() {
        ApiResponse<AuthDtos.MessageResponse> result = controller.logout(null, http, response);
        assertEquals(SecurityMessages.GENERIC_LOGOUT_MESSAGE, result.data().message());
    }
}
