package com.byonix.shoplink.service.security;

import com.byonix.shoplink.domain.entity.RefreshToken;
import com.byonix.shoplink.domain.entity.User;
import com.byonix.shoplink.domain.enums.RefreshSessionScope;
import com.byonix.shoplink.domain.enums.SecurityEventSeverity;
import com.byonix.shoplink.domain.enums.SecurityEventType;
import com.byonix.shoplink.repository.RefreshTokenRepository;
import com.byonix.shoplink.repository.UserRepository;
import com.byonix.shoplink.security.login.GenericAuthException;
import com.byonix.shoplink.security.ratelimit.RateLimitService;
import com.byonix.shoplink.security.request.ClientRequestContext;
import com.byonix.shoplink.service.TokenHashService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class RefreshTokenSecurityService {
    private final RefreshTokenRepository refreshTokenRepository;
    private final TokenHashService tokenHashService;
    private final RateLimitService rateLimitService;
    private final SecurityEventService securityEventService;
    private final SecurityAlertService securityAlertService;
    private final RiskAssessmentService riskAssessmentService;
    private final UserRepository userRepository;

    @Transactional(noRollbackFor = GenericAuthException.class)
    public RefreshToken resolveForRefresh(String rawToken, ClientRequestContext ctx, RefreshSessionScope expectedScope) {
        rateLimitService.checkRefreshByIp(ctx.ipAddress());
        String hash = tokenHashService.hash(rawToken);
        Optional<RefreshToken> existingOpt = refreshTokenRepository.findByTokenHash(hash);

        if (existingOpt.isEmpty()) {
            throw new GenericAuthException();
        }

        RefreshToken existing = existingOpt.get();

        if (existing.getSessionScope() != expectedScope) {
            throw new GenericAuthException();
        }

        if (existing.getRevokedAt() != null) {
            handleReuse(existing, ctx);
            throw new GenericAuthException();
        }

        if (existing.getExpiresAt().isBefore(Instant.now()) || !existing.getUser().isActive()) {
            throw new GenericAuthException();
        }

        return existing;
    }

    @Transactional
    public void handleReuse(RefreshToken revokedToken, ClientRequestContext ctx) {
        User user = revokedToken.getUser();
        revokedToken.setReuseDetectedAt(Instant.now());
        refreshTokenRepository.save(revokedToken);
        revokeAllSessions(user);
        int risk = riskAssessmentService.calculateLoginRisk(user, user.getEmail(), ctx, false, false, true);
        securityEventService.log(SecurityEventType.REFRESH_TOKEN_REUSE_DETECTED, SecurityEventSeverity.CRITICAL,
                user, null, ctx.ipAddress(), ctx.userAgent(), Map.of("riskScore", risk));
        securityAlertService.sendSecurityAlert(SecurityEventType.REFRESH_TOKEN_REUSE_DETECTED, user,
                user.getEmail(), risk, Map.of("ip", ctx.ipAddress()));
        user.setSuspiciousActivityFlag(true);
        userRepository.save(user);
    }

    @Transactional
    public void revokeAllSessions(User user) {
        refreshTokenRepository.findByUser_IdAndRevokedAtIsNull(user.getId()).forEach(token -> {
            if (token.getRevokedAt() == null) {
                token.setRevokedAt(Instant.now());
            }
        });
    }

    public ClientRequestContext context(HttpServletRequest request) {
        return ClientRequestContext.from(request);
    }
}
