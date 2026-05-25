package com.byonix.shoplink.service.security;

import com.byonix.shoplink.api.dto.SecurityDtos;
import com.byonix.shoplink.domain.entity.RefreshToken;
import com.byonix.shoplink.domain.entity.User;
import com.byonix.shoplink.domain.enums.SecurityEventSeverity;
import com.byonix.shoplink.domain.enums.SecurityEventType;
import com.byonix.shoplink.repository.RefreshTokenRepository;
import com.byonix.shoplink.repository.UserRepository;
import com.byonix.shoplink.security.login.GenericAuthException;
import com.byonix.shoplink.security.login.SecurityActionException;
import com.byonix.shoplink.service.TokenHashService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthSecurityService {
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenHashService tokenHashService;
    private final SecurityEventService securityEventService;
    private final RefreshTokenSecurityService refreshTokenSecurityService;

    public List<SecurityDtos.SessionResponse> listSessions(User user) {
        return refreshTokenRepository.findByUser_IdAndRevokedAtIsNull(user.getId()).stream()
                .filter(t -> t.getExpiresAt().isAfter(Instant.now()))
                .map(this::toSession)
                .toList();
    }

    @Transactional
    public void revokeSession(User user, UUID sessionId) {
        RefreshToken token = refreshTokenRepository.findById(sessionId)
                .orElseThrow(SecurityActionException::invalidToken);
        if (!token.getUser().getId().equals(user.getId())) {
            throw new SecurityActionException(SecurityActionException.GENERIC_TOKEN_MESSAGE);
        }
        if (token.getRevokedAt() == null) {
            token.setRevokedAt(Instant.now());
            refreshTokenRepository.save(token);
        }
    }

    @Transactional
    public void logoutAll(User user, String ip, String userAgent) {
        refreshTokenSecurityService.revokeAllSessions(user);
        user.setTokenVersion(user.getTokenVersion() + 1);
        userRepository.save(user);
        securityEventService.log(SecurityEventType.LOGOUT_ALL, SecurityEventSeverity.INFO, user, null,
                ip, userAgent, null);
    }

    @Transactional
    public void changePassword(User user, String currentPassword, String newPassword, String ip, String userAgent) {
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new GenericAuthException();
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setPasswordChangedAt(Instant.now());
        user.setTokenVersion(user.getTokenVersion() + 1);
        user.setForcePasswordReset(false);
        userRepository.save(user);
        refreshTokenSecurityService.revokeAllSessions(user);
        securityEventService.log(SecurityEventType.PASSWORD_CHANGED, SecurityEventSeverity.INFO, user, null,
                ip, userAgent, null);
    }

    public Page<com.byonix.shoplink.domain.entity.SecurityEvent> userEvents(User user, Pageable pageable) {
        return securityEventService.forUser(user.getId(), pageable);
    }

    private SecurityDtos.SessionResponse toSession(RefreshToken token) {
        return new SecurityDtos.SessionResponse(
                token.getId(),
                token.getIpAddress(),
                token.getUserAgent(),
                token.getCreatedAt(),
                token.getExpiresAt(),
                token.getRevokedAt() != null);
    }
}
