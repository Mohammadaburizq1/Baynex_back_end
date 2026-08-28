package com.byonix.shoplink.service.security;

import com.byonix.shoplink.api.dto.SecurityDtos;
import com.byonix.shoplink.config.AuthProperties;
import com.byonix.shoplink.config.LoginSecurityProperties;
import com.byonix.shoplink.domain.entity.User;
import com.byonix.shoplink.domain.enums.SecurityEventSeverity;
import com.byonix.shoplink.domain.enums.SecurityEventType;
import com.byonix.shoplink.repository.UserRepository;
import com.byonix.shoplink.security.JwtService;
import com.byonix.shoplink.security.login.GenericAuthException;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.exceptions.QrGenerationException;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.qr.QrGenerator;
import dev.samstevens.totp.qr.ZxingPngQrGenerator;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Base64;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MfaChallengeService {
    private static final String DEV_BYPASS_CODE = "000000";
    private static final String ISSUER = "ShopLink";

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final AuthProperties authProperties;
    private final LoginSecurityProperties loginSecurityProperties;
    private final SecurityEventService securityEventService;
    private final TotpService totpService;

    private final SecretGenerator secretGenerator = new DefaultSecretGenerator();
    private final QrGenerator qrGenerator = new ZxingPngQrGenerator();

    public boolean requiresMfaBeforeTokens(User user) {
        return authProperties.isRequireSuperAdminMfa() && user.getRole().requiresMfaChallenge();
    }

    public String createChallenge(User user) {
        return jwtService.createMfaChallengeToken(user);
    }

    /**
     * First-time enrollment only. Reuses the mfaChallengeToken from /login (already proves the password
     * was correct) so an admin can enroll before ever completing a full session — otherwise there's no way
     * to bootstrap MFA once requireSuperAdminMfa is on and no secret exists yet. Rejects re-enrollment once
     * a secret is already set; rotating an existing device isn't handled by this endpoint.
     */
    @Transactional
    public SecurityDtos.MfaSetupResponse setupMfa(String challengeToken) {
        Claims claims;
        User user;
        try {
            claims = jwtService.parseMfaChallenge(challengeToken);
            UUID userId = jwtService.userId(claims);
            user = userRepository.findById(userId).orElseThrow(() -> new GenericAuthException());
        } catch (GenericAuthException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new GenericAuthException();
        }
        if (!user.isActive() || !user.getRole().isAdmin()) {
            throw new GenericAuthException();
        }
        if (user.getMfaSecret() != null && !user.getMfaSecret().isBlank()) {
            throw new IllegalArgumentException("MFA is already configured for this account.");
        }

        String secret = secretGenerator.generate();
        QrData qrData = new QrData.Builder()
                .label(user.getEmail() != null ? user.getEmail() : user.getPhone())
                .secret(secret)
                .issuer(ISSUER)
                .algorithm(HashingAlgorithm.SHA1)
                .digits(6)
                .period(30)
                .build();

        byte[] qrImage;
        try {
            qrImage = qrGenerator.generate(qrData);
        } catch (QrGenerationException e) {
            throw new IllegalStateException("Could not generate QR code.", e);
        }
        String dataUri = "data:" + qrGenerator.getImageMimeType() + ";base64," + Base64.getEncoder().encodeToString(qrImage);

        user.setMfaSecret(secret);
        user.setMfaEnabled(true);

        return new SecurityDtos.MfaSetupResponse(secret, qrData.getUri(), dataUri);
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
        if (!user.isMfaEnabled() && !user.getRole().requiresMfaChallenge()) {
            return;
        }
        if (mfaCode == null || mfaCode.isBlank()) {
            securityEventService.log(SecurityEventType.MFA_FAILED, SecurityEventSeverity.WARN, user, null,
                    null, null, Map.of("reason", "missing_code"));
            throw new GenericAuthException();
        }
        // Dev bypass is its own flag (app.security.login.mfa-dev-bypass / MFA_DEV_BYPASS),
        // never tied to expose-tokens-in-response, and is fatal to boot in prod (see ProductionSecurityValidator).
        if (loginSecurityProperties.isMfaDevBypass() && DEV_BYPASS_CODE.equals(mfaCode)) {
            return;
        }
        if (totpService.isValid(user.getMfaSecret(), mfaCode)) {
            return;
        }
        securityEventService.log(SecurityEventType.MFA_FAILED, SecurityEventSeverity.WARN, user, null,
                null, null, Map.of("reason", "invalid_code"));
        throw new GenericAuthException();
    }
}
