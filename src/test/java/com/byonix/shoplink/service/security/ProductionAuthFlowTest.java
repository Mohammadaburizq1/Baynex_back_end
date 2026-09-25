package com.byonix.shoplink.service.security;

import com.byonix.shoplink.config.AuthProperties;
import com.byonix.shoplink.config.MailProperties;
import com.byonix.shoplink.domain.entity.User;
import com.byonix.shoplink.domain.enums.Role;
import com.byonix.shoplink.repository.*;
import com.byonix.shoplink.security.JwtService;
import com.byonix.shoplink.security.RefreshTokenCookieService;
import com.byonix.shoplink.security.RefreshTokenCredentialResolver;
import com.byonix.shoplink.security.ratelimit.RateLimitService;
import com.byonix.shoplink.service.AuthService;
import com.byonix.shoplink.service.MapperService;
import com.byonix.shoplink.service.TokenHashService;
import com.byonix.shoplink.service.notification.EmailNotificationService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductionAuthFlowTest {
    @Mock UserRepository userRepository;
    @Mock EmailVerificationTokenRepository emailVerificationTokenRepository;
    @Mock PasswordResetTokenRepository passwordResetTokenRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock RateLimitService rateLimitService;
    @Mock HttpServletRequest httpServletRequest;
    @Mock jakarta.servlet.http.HttpServletResponse httpServletResponse;

    AuthService authService;

    @BeforeEach
    void setUp() {
        AuthProperties authProperties = new AuthProperties();
        authProperties.setExposeTokensInResponse(false);
        DevTokenLogger devTokenLogger = new DevTokenLogger(authProperties);
        AuthResponseFactory factory = new AuthResponseFactory(authProperties, mock(MapperService.class));

        authService = new AuthService(
                userRepository,
                mock(RefreshTokenRepository.class),
                emailVerificationTokenRepository,
                passwordResetTokenRepository,
                passwordEncoder,
                mock(JwtService.class),
                mock(TokenHashService.class),
                mock(SecureLoginService.class),
                mock(RefreshTokenSecurityService.class),
                rateLimitService,
                mock(SecurityEventService.class),
                factory,
                mock(RefreshTokenCookieService.class),
                mock(RefreshTokenCredentialResolver.class),
                devTokenLogger,
                mock(EmailNotificationService.class),
                new MailProperties(),
                mock(com.byonix.shoplink.security.google.GoogleTokenVerifier.class),
                mock(com.byonix.shoplink.service.PasswordHashService.class),
                mock(OtpService.class));
        ReflectionTestUtils.setField(authService, "emailVerificationHours", 48L);
        ReflectionTestUtils.setField(authService, "passwordResetMinutes", 15L);
    }

    @Test
    void forgotPasswordNeverReturnsTokenInProduction() {
        User user = new User();
        user.setId(java.util.UUID.randomUUID());
        user.setActive(true);
        user.setRole(Role.MERCHANT_OWNER);
        when(userRepository.findByEmailIgnoreCase(any())).thenReturn(java.util.Optional.of(user));
        when(httpServletRequest.getRemoteAddr()).thenReturn("127.0.0.1");

        var response = authService.forgotPassword("merchant@example.com", httpServletRequest);
        org.junit.jupiter.api.Assertions.assertEquals(
                "Request received. If eligible, check your inbox for password reset instructions.",
                response.message());
    }
}
