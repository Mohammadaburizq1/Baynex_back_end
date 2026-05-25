package com.byonix.shoplink.service.security;

import com.byonix.shoplink.domain.entity.RefreshToken;
import com.byonix.shoplink.domain.entity.User;
import com.byonix.shoplink.domain.enums.RefreshSessionScope;
import com.byonix.shoplink.domain.enums.SecurityEventType;
import com.byonix.shoplink.repository.RefreshTokenRepository;
import com.byonix.shoplink.repository.UserRepository;
import com.byonix.shoplink.security.login.GenericAuthException;
import com.byonix.shoplink.security.ratelimit.RateLimitService;
import com.byonix.shoplink.security.request.ClientRequestContext;
import com.byonix.shoplink.service.TokenHashService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RefreshTokenSecurityServiceTest {
    @Mock RefreshTokenRepository refreshTokenRepository;
    @Mock TokenHashService tokenHashService;
    @Mock RateLimitService rateLimitService;
    @Mock SecurityEventService securityEventService;
    @Mock SecurityAlertService securityAlertService;
    @Mock RiskAssessmentService riskAssessmentService;
    @Mock UserRepository userRepository;
    @InjectMocks RefreshTokenSecurityService service;

    @Test
    void reuseRevokesAllSessionsAndThrowsGeneric() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("u@example.com");
        RefreshToken token = new RefreshToken();
        token.setUser(user);
        token.setRevokedAt(Instant.now());
        token.setExpiresAt(Instant.now().plusSeconds(3600));

        when(tokenHashService.hash("raw")).thenReturn("hash");
        when(refreshTokenRepository.findByTokenHash("hash")).thenReturn(Optional.of(token));
        when(refreshTokenRepository.findByUser_IdAndRevokedAtIsNull(user.getId())).thenReturn(List.of(token));
        when(riskAssessmentService.calculateLoginRisk(any(), any(), any(), anyBoolean(), anyBoolean(), eq(true))).thenReturn(100);

        ClientRequestContext ctx = new ClientRequestContext("10.0.0.1", "ua", null, null, null);
        assertThrows(GenericAuthException.class, () -> service.resolveForRefresh("raw", ctx, RefreshSessionScope.MERCHANT));
        verify(securityEventService).log(eq(SecurityEventType.REFRESH_TOKEN_REUSE_DETECTED), any(), eq(user), isNull(), any(), any(), any());
        verify(userRepository).save(user);
    }

    @Test
    void rejectsAdminTokenOnMerchantRefresh() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setActive(true);
        RefreshToken token = new RefreshToken();
        token.setUser(user);
        token.setSessionScope(RefreshSessionScope.ADMIN);
        token.setExpiresAt(Instant.now().plusSeconds(3600));

        when(tokenHashService.hash("raw")).thenReturn("hash");
        when(refreshTokenRepository.findByTokenHash("hash")).thenReturn(Optional.of(token));

        ClientRequestContext ctx = new ClientRequestContext("10.0.0.1", "ua", null, null, null);
        assertThrows(GenericAuthException.class, () -> service.resolveForRefresh("raw", ctx, RefreshSessionScope.MERCHANT));
    }

    @Test
    void rejectsMerchantTokenOnAdminRefresh() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setActive(true);
        RefreshToken token = new RefreshToken();
        token.setUser(user);
        token.setSessionScope(RefreshSessionScope.MERCHANT);
        token.setExpiresAt(Instant.now().plusSeconds(3600));

        when(tokenHashService.hash("raw")).thenReturn("hash");
        when(refreshTokenRepository.findByTokenHash("hash")).thenReturn(Optional.of(token));

        ClientRequestContext ctx = new ClientRequestContext("10.0.0.1", "ua", null, null, null);
        assertThrows(GenericAuthException.class, () -> service.resolveForRefresh("raw", ctx, RefreshSessionScope.ADMIN));
    }
}
