package com.byonix.shoplink.service.security;

import com.byonix.shoplink.config.LoginSecurityProperties;
import com.byonix.shoplink.domain.entity.User;
import com.byonix.shoplink.domain.enums.Role;
import com.byonix.shoplink.repository.UserRepository;
import com.byonix.shoplink.security.login.GenericAuthException;
import com.byonix.shoplink.security.login.LoginPortal;
import com.byonix.shoplink.security.ratelimit.RateLimitService;
import com.byonix.shoplink.security.request.ClientRequestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SecureLoginServiceTest {
    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock RateLimitService rateLimitService;
    @Mock IpBlocklistService ipBlocklistService;
    @Mock AccountLockService accountLockService;
    @Mock LoginAttemptService loginAttemptService;
    @Mock RiskAssessmentService riskAssessmentService;
    @Mock SecurityEventService securityEventService;
    @Mock SecurityAlertService securityAlertService;
    @InjectMocks SecureLoginService secureLoginService;

    private final LoginSecurityProperties props = new LoginSecurityProperties();
    private User user;
    private ClientRequestContext ctx;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(secureLoginService, "props", props);
        ReflectionTestUtils.setField(secureLoginService, "requireEmailVerificationForLogin", false);
        user = new User();
        user.setId(java.util.UUID.randomUUID());
        user.setEmail("merchant@example.com");
        user.setPasswordHash("hash");
        user.setRole(Role.MERCHANT_OWNER);
        user.setActive(true);
        ctx = new ClientRequestContext("127.0.0.1", "JUnit", null, null, null);
    }

    @Test
    void loginSuccess() {
        when(ipBlocklistService.isBlocked(any())).thenReturn(false);
        when(loginAttemptService.tooManyFailuresForIpPerMinute(any())).thenReturn(false);
        when(loginAttemptService.tooManyFailuresForIpPerHour(any())).thenReturn(false);
        when(loginAttemptService.tooManyFailuresForEmail(any())).thenReturn(false);
        when(userRepository.findByEmailIgnoreCase(any())).thenReturn(Optional.of(user));
        when(accountLockService.isLocked(user)).thenReturn(false);
        when(passwordEncoder.matches(any(), any())).thenReturn(true);
        when(riskAssessmentService.calculateLoginRisk(any(), any(), any(), anyBoolean(), anyBoolean(), anyBoolean())).thenReturn(0);
        when(riskAssessmentService.requiresExtraVerification(0)).thenReturn(false);
        when(riskAssessmentService.shouldAlert(0)).thenReturn(false);

        var outcome = secureLoginService.authenticate("merchant@example.com", "pass", ctx, LoginPortal.MERCHANT);
        assertEquals(user.getId(), outcome.user().getId());
        verify(accountLockService).recordSuccessfulLogin(eq(user), any(), any());
    }

    @Test
    void loginFailureIncrementsAndGenericError() {
        when(ipBlocklistService.isBlocked(any())).thenReturn(false);
        when(loginAttemptService.tooManyFailuresForIpPerMinute(any())).thenReturn(false);
        when(loginAttemptService.tooManyFailuresForIpPerHour(any())).thenReturn(false);
        when(loginAttemptService.tooManyFailuresForEmail(any())).thenReturn(false);
        when(userRepository.findByEmailIgnoreCase(any())).thenReturn(Optional.of(user));
        when(accountLockService.isLocked(user)).thenReturn(false);
        when(passwordEncoder.matches(any(), any())).thenReturn(false);
        when(riskAssessmentService.calculateLoginRisk(any(), any(), any(), eq(true), anyBoolean(), anyBoolean())).thenReturn(20);

        GenericAuthException ex = assertThrows(GenericAuthException.class,
                () -> secureLoginService.authenticate("merchant@example.com", "wrong", ctx, LoginPortal.MERCHANT));
        assertEquals(GenericAuthException.MESSAGE, ex.getMessage());
        verify(accountLockService).recordFailedLogin(eq(user), any(), any());
    }

    @Test
    void unknownEmailSameGenericError() {
        when(ipBlocklistService.isBlocked(any())).thenReturn(false);
        when(loginAttemptService.tooManyFailuresForIpPerMinute(any())).thenReturn(false);
        when(loginAttemptService.tooManyFailuresForIpPerHour(any())).thenReturn(false);
        when(loginAttemptService.tooManyFailuresForEmail(any())).thenReturn(false);
        when(userRepository.findByEmailIgnoreCase(any())).thenReturn(Optional.empty());
        when(riskAssessmentService.calculateLoginRisk(isNull(), any(), any(), eq(true), anyBoolean(), anyBoolean())).thenReturn(20);

        assertThrows(GenericAuthException.class,
                () -> secureLoginService.authenticate("unknown@example.com", "pass", ctx, LoginPortal.MERCHANT));
    }
}
