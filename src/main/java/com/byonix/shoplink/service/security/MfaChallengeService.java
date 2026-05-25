package com.byonix.shoplink.service.security;

import com.byonix.shoplink.config.AuthProperties;
import com.byonix.shoplink.domain.entity.User;
import com.byonix.shoplink.domain.enums.SecurityEventSeverity;
import com.byonix.shoplink.domain.enums.SecurityEventType;
import com.byonix.shoplink.repository.UserRepository;
import com.byonix.shoplink.security.JwtService;
import com.byonix.shoplink.security.login.GenericAuthException;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MfaChallengeService {
    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final AuthProperties authProperties;
    private final SecurityEventService securityEventService;

    public boolean requiresMfaBeforeTokens(User user) {
        return authProperties.isRequireSuperAdminMfa() && user.getRole().requiresMfaChallenge();
    }

    public String createChallenge(User user) {
        return jwtService.createMfaChallengeToken(user);
    }

    public User verifyChallengeAndCode(String challengeToken, String mfaCode, String ip, String userAgent) {
        try {
            Claims claims = jwtService.parseMfaChallenge(challengeToken);
            UUID userId = jwtService.userId(claims);
            User user = userRepository.findById(userId).orElseThrow(() -> new GenericAuthException());
            if (!user.isActive() || !user.getRole().isAdmin()) {
                throw new GenericAuthException();
            }
            validateTotp(user, mfaCode);
            securityEventService.log(SecurityEventType.MFA_SUCCESS, SecurityEventSeverity.INFO, user, null,
                    ip, userAgent, null);
            return user;
        } catch (GenericAuthException e) {
            throw e;
        } catch (RuntimeException e) {
            securityEventService.log(SecurityEventType.MFA_FAILED, SecurityEventSeverity.WARN, null, null,
                    ip, userAgent, Map.of("reason", "invalid_challenge"));
            throw new GenericAuthException();
        }
    }

    @Transactional
    public void validateTotp(User user, String mfaCode) {
        // TODO: TOTP MFA using authenticator apps (Google Authenticator, Authy)
        if (!user.isMfaEnabled() && !user.getRole().requiresMfaChallenge()) {
            return;
        }
        if (mfaCode == null || mfaCode.isBlank()) {
            securityEventService.log(SecurityEventType.MFA_FAILED, SecurityEventSeverity.WARN, user, null,
                    null, null, Map.of("reason", "missing_code"));
            throw new GenericAuthException();
        }
        // Dev bypass only when explicitly enabled (never in production)
        if (authProperties.isExposeTokensInResponse() && "000000".equals(mfaCode)) {
            return;
        }
        // TODO: verify against user.getMfaSecret()
        securityEventService.log(SecurityEventType.MFA_FAILED, SecurityEventSeverity.WARN, user, null,
                null, null, Map.of("reason", "invalid_code"));
        throw new GenericAuthException();
    }
}
