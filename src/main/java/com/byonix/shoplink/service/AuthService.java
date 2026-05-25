package com.byonix.shoplink.service;

import com.byonix.shoplink.api.dto.AuthDtos;
import com.byonix.shoplink.config.MailProperties;
import com.byonix.shoplink.domain.entity.EmailVerificationToken;
import com.byonix.shoplink.domain.entity.PasswordResetToken;
import com.byonix.shoplink.domain.entity.RefreshToken;
import com.byonix.shoplink.domain.entity.User;
import com.byonix.shoplink.domain.enums.RefreshSessionScope;
import com.byonix.shoplink.domain.enums.Role;
import com.byonix.shoplink.domain.enums.SecurityEventSeverity;
import com.byonix.shoplink.domain.enums.SecurityEventType;
import com.byonix.shoplink.repository.EmailVerificationTokenRepository;
import com.byonix.shoplink.repository.PasswordResetTokenRepository;
import com.byonix.shoplink.repository.RefreshTokenRepository;
import com.byonix.shoplink.repository.UserRepository;
import com.byonix.shoplink.security.JwtService;
import com.byonix.shoplink.security.RefreshTokenCookieService;
import com.byonix.shoplink.security.RefreshTokenCredentialResolver;
import com.byonix.shoplink.security.login.SecurityActionException;
import com.byonix.shoplink.security.ratelimit.RateLimitService;
import com.byonix.shoplink.security.request.ClientRequestContext;
import com.byonix.shoplink.service.notification.EmailNotificationService;
import com.byonix.shoplink.service.security.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final EmailVerificationTokenRepository emailVerificationTokenRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final TokenHashService tokenHashService;
    private final SecureLoginService secureLoginService;
    private final RefreshTokenSecurityService refreshTokenSecurityService;
    private final RateLimitService rateLimitService;
    private final SecurityEventService securityEventService;
    private final AuthResponseFactory authResponseFactory;
    private final RefreshTokenCookieService refreshTokenCookieService;
    private final RefreshTokenCredentialResolver refreshTokenCredentialResolver;
    private final DevTokenLogger devTokenLogger;
    private final EmailNotificationService emailNotificationService;
    private final MailProperties mailProperties;

    @Value("${app.jwt.refresh-expiration-days}")
    private long refreshDays;

    @Value("${app.jwt.admin-refresh-expiration-days:1}")
    private long adminRefreshDays;

    @Value("${app.jwt.customer-refresh-expiration-days:30}")
    private long customerRefreshExpirationDays;

    @Value("${app.auth.email-verification-expiration-hours:48}")
    private long emailVerificationHours;

    @Value("${app.auth.password-reset-expiration-minutes:15}")
    private long passwordResetMinutes;

    @Transactional
    public AuthDtos.AuthResponse register(AuthDtos.RegisterRequest request, HttpServletRequest http,
                                          HttpServletResponse response) {
        ClientRequestContext ctx = ClientRequestContext.from(http);
        rateLimitService.checkRegisterByIp(ctx.ipAddress());
        String email = normalizeEmail(request.email());
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new IllegalArgumentException("Email already registered");
        }
        User user = new User();
        user.setFullName(request.fullName().trim());
        user.setEmail(email);
        user.setPhone(blankToNull(request.phone()));
        user.setRole(Role.MERCHANT_OWNER);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setPasswordChangedAt(Instant.now());
        userRepository.save(user);

        emailVerificationTokenRepository.deleteByUserId(user.getId());
        String verifyRaw = tokenHashService.randomRefreshToken();
        EmailVerificationToken evt = new EmailVerificationToken();
        evt.setUser(user);
        evt.setTokenHash(tokenHashService.hash(verifyRaw));
        evt.setExpiresAt(Instant.now().plusSeconds(emailVerificationHours * 3600));
        emailVerificationTokenRepository.save(evt);
        devTokenLogger.logEmailVerificationToken(email, verifyRaw);
        emailNotificationService.sendEmailVerification(email, verifyRaw);

        return issue(user, http, response, 0, false, RefreshSessionScope.MERCHANT);
    }

    @Transactional
    public AuthDtos.AuthResponse login(AuthDtos.LoginRequest request, HttpServletRequest http,
                                       HttpServletResponse response) {
        ClientRequestContext ctx = ClientRequestContext.from(http);
        String email = normalizeEmail(request.email());
        SecureLoginService.LoginOutcome outcome = secureLoginService.authenticate(
                email, request.password(), ctx, com.byonix.shoplink.security.login.LoginPortal.MERCHANT);
        return issue(outcome.user(), http, response, outcome.riskScore(), outcome.extraVerificationRequired(),
                RefreshSessionScope.MERCHANT);
    }

    @Transactional
    public AuthDtos.AuthResponse refresh(AuthDtos.RefreshRequest request, HttpServletRequest http,
                                         HttpServletResponse response) {
        return refresh(request, http, response, RefreshSessionScope.MERCHANT);
    }

    @Transactional
    public AuthDtos.AuthResponse refresh(AuthDtos.RefreshRequest request, HttpServletRequest http,
                                         HttpServletResponse response, RefreshSessionScope scope) {
        ClientRequestContext ctx = ClientRequestContext.from(http);
        String raw = refreshTokenCredentialResolver.resolve(
                request != null ? request.refreshToken() : null, http, scope);
        RefreshToken existing = refreshTokenSecurityService.resolveForRefresh(raw, ctx, scope);
        String newRaw = tokenHashService.randomRefreshToken();
        String newHash = tokenHashService.hash(newRaw);
        existing.setRevokedAt(Instant.now());
        existing.setReplacedByTokenHash(newHash);
        refreshTokenRepository.save(existing);
        refreshTokenRepository.save(buildRefreshToken(existing.getUser(), newRaw, http, scope));
        refreshTokenCookieService.writeRefreshCookie(response, newRaw, scope);
        return authResponseFactory.authenticated(
                existing.getUser(),
                jwtService.createAccessToken(existing.getUser()),
                newRaw,
                null,
                false);
    }

    @Transactional
    public void logout(AuthDtos.LogoutRequest request, HttpServletRequest http, HttpServletResponse response) {
        logout(request, http, response, RefreshSessionScope.MERCHANT);
    }

    public void logout(AuthDtos.LogoutRequest request, HttpServletRequest http, HttpServletResponse response,
                       RefreshSessionScope scope) {
        try {
            String raw = refreshTokenCredentialResolver.resolve(
                    request != null ? request.refreshToken() : null, http, scope);
            refreshTokenRepository.findByTokenHash(tokenHashService.hash(raw)).ifPresent(token -> {
                if (token.getRevokedAt() == null) {
                    token.setRevokedAt(Instant.now());
                }
            });
        } catch (com.byonix.shoplink.security.login.GenericAuthException ignored) {
            // Still clear cookies if credentials missing
        }
        refreshTokenCookieService.clearRefreshCookie(response, scope);
    }

    @Transactional
    public void verifyEmail(String rawToken) {
        consumeVerificationToken(rawToken);
    }

    @Transactional
    public AuthDtos.AuthActionResponse resendVerificationEmail(String email, HttpServletRequest http) {
        ClientRequestContext ctx = ClientRequestContext.from(http);
        rateLimitService.checkVerifyResendByIp(ctx.ipAddress());
        String normalized = normalizeEmail(email);
        rateLimitService.checkVerifyResendByEmail(normalized);
        userRepository.findByEmailIgnoreCase(normalized)
                .filter(u -> u.getEmailVerifiedAt() == null)
                .filter(User::isActive)
                .ifPresent(user -> {
                    emailVerificationTokenRepository.deleteByUserId(user.getId());
                    String raw = tokenHashService.randomRefreshToken();
                    EmailVerificationToken evt = new EmailVerificationToken();
                    evt.setUser(user);
                    evt.setTokenHash(tokenHashService.hash(raw));
                    evt.setExpiresAt(Instant.now().plusSeconds(emailVerificationHours * 3600));
                    emailVerificationTokenRepository.save(evt);
                    devTokenLogger.logEmailVerificationToken(normalized, raw);
                    emailNotificationService.sendEmailVerification(normalized, raw);
                });
        return authResponseFactory.actionMessage(SecurityActionException.GENERIC_VERIFY_RESEND_MESSAGE);
    }

    @Transactional
    public AuthDtos.AuthActionResponse forgotPassword(String email, HttpServletRequest http) {
        return forgotPassword(email, http, Role::isMerchant, mailProperties.getPasswordResetPath());
    }

    @Transactional
    public AuthDtos.AuthActionResponse forgotCustomerPassword(String email, HttpServletRequest http) {
        return forgotPassword(email, http, Role::isCustomer, mailProperties.getPasswordResetPath());
    }

    @Transactional
    public void resetCustomerPassword(String rawToken, String newPassword, HttpServletRequest http) {
        resetPassword(rawToken, newPassword, http, Role::isCustomer);
    }

    @Transactional
    public AuthDtos.AuthActionResponse forgotAdminPassword(String email, HttpServletRequest http) {
        return forgotPassword(email, http, Role::isAdmin, mailProperties.getAdminPasswordResetPath());
    }

    @Transactional
    public AuthDtos.AuthActionResponse forgotPassword(String email, HttpServletRequest http,
                                                      java.util.function.Predicate<Role> roleFilter,
                                                      String resetPath) {
        ClientRequestContext ctx = ClientRequestContext.from(http);
        rateLimitService.checkForgotPasswordByIp(ctx.ipAddress());
        String normalized = normalizeEmail(email);
        rateLimitService.checkForgotPasswordByEmail(normalized);
        userRepository.findByEmailIgnoreCase(normalized)
                .filter(User::isActive)
                .filter(u -> roleFilter.test(u.getRole()))
                .ifPresent(user -> {
                    passwordResetTokenRepository.deleteByUserId(user.getId());
                    String raw = tokenHashService.randomRefreshToken();
                    PasswordResetToken pr = new PasswordResetToken();
                    pr.setUser(user);
                    pr.setTokenHash(tokenHashService.hash(raw));
                    pr.setExpiresAt(Instant.now().plusSeconds(passwordResetMinutes * 60));
                    passwordResetTokenRepository.save(pr);
                    devTokenLogger.logPasswordResetToken(normalized, raw);
                    emailNotificationService.sendPasswordResetEmail(normalized, raw, resetPath);
                    securityEventService.log(SecurityEventType.PASSWORD_RESET_REQUESTED, SecurityEventSeverity.INFO,
                            user, null, ctx.ipAddress(), ctx.userAgent(), null);
                });
        return authResponseFactory.actionMessage(SecurityActionException.GENERIC_RESET_MESSAGE);
    }

    @Transactional
    public void resetPassword(String rawToken, String newPassword, HttpServletRequest http) {
        resetPassword(rawToken, newPassword, http, Role::isMerchant);
    }

    @Transactional
    public void resetAdminPassword(String rawToken, String newPassword, HttpServletRequest http) {
        resetPassword(rawToken, newPassword, http, Role::isAdmin);
    }

    @Transactional
    public void resetPassword(String rawToken, String newPassword, HttpServletRequest http,
                              java.util.function.Predicate<Role> roleFilter) {
        ClientRequestContext ctx = ClientRequestContext.from(http);
        rateLimitService.checkResetPasswordByIp(ctx.ipAddress());
        String hash = tokenHashService.hash(rawToken);
        PasswordResetToken pr = passwordResetTokenRepository.findByTokenHash(hash)
                .orElseThrow(SecurityActionException::invalidToken);
        if (pr.getConsumedAt() != null || pr.getExpiresAt().isBefore(Instant.now())) {
            throw SecurityActionException.invalidToken();
        }
        User user = userRepository.findById(pr.getUser().getId()).orElseThrow(SecurityActionException::invalidToken);
        if (!roleFilter.test(user.getRole())) {
            throw SecurityActionException.invalidToken();
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setPasswordChangedAt(Instant.now());
        user.setTokenVersion(user.getTokenVersion() + 1);
        user.setForcePasswordReset(false);
        pr.setConsumedAt(Instant.now());
        passwordResetTokenRepository.save(pr);
        passwordResetTokenRepository.deleteByUserId(user.getId());
        refreshTokenSecurityService.revokeAllSessions(user);
        userRepository.save(user);
        securityEventService.log(SecurityEventType.PASSWORD_RESET_COMPLETED, SecurityEventSeverity.INFO,
                user, null, ctx.ipAddress(), ctx.userAgent(), null);
    }

    public AuthDtos.AuthResponse issue(User user, HttpServletRequest http, HttpServletResponse response,
                                       Integer riskScore, boolean extraVerification) {
        return issue(user, http, response, riskScore, extraVerification, RefreshSessionScope.MERCHANT);
    }

    public AuthDtos.AuthResponse issue(User user, HttpServletRequest http, HttpServletResponse response,
                                       Integer riskScore, boolean extraVerification, RefreshSessionScope scope) {
        String rawRefresh = tokenHashService.randomRefreshToken();
        refreshTokenRepository.save(buildRefreshToken(user, rawRefresh, http, scope));
        refreshTokenCookieService.writeRefreshCookie(response, rawRefresh, scope);
        return authResponseFactory.authenticated(
                user,
                jwtService.createAccessToken(user),
                rawRefresh,
                riskScore,
                extraVerification);
    }

    private void consumeVerificationToken(String rawToken) {
        String hash = tokenHashService.hash(rawToken);
        EmailVerificationToken evt = emailVerificationTokenRepository.findByTokenHash(hash)
                .orElseThrow(SecurityActionException::invalidToken);
        if (evt.getConsumedAt() != null || evt.getExpiresAt().isBefore(Instant.now())) {
            throw SecurityActionException.invalidToken();
        }
        User user = userRepository.findById(evt.getUser().getId()).orElseThrow(SecurityActionException::invalidToken);
        evt.setConsumedAt(Instant.now());
        emailVerificationTokenRepository.save(evt);
        user.setEmailVerifiedAt(Instant.now());
        emailVerificationTokenRepository.deleteByUserId(user.getId());
        userRepository.save(user);
        securityEventService.log(SecurityEventType.EMAIL_VERIFIED, SecurityEventSeverity.INFO, user, null, null, null, null);
    }

    private RefreshToken buildRefreshToken(User user, String raw, HttpServletRequest http, RefreshSessionScope scope) {
        ClientRequestContext ctx = ClientRequestContext.from(http);
        long days = switch (scope) {
            case ADMIN -> adminRefreshDays;
            case CUSTOMER -> customerRefreshExpirationDays;
            case MERCHANT -> refreshDays;
        };
        RefreshToken token = new RefreshToken();
        token.setUser(user);
        token.setTokenHash(tokenHashService.hash(raw));
        token.setExpiresAt(Instant.now().plusSeconds(days * 86400));
        token.setSessionScope(scope);
        token.setIpAddress(ctx.ipAddress());
        token.setUserAgent(ctx.userAgent());
        return token;
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
