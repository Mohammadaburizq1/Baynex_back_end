package com.byonix.shoplink.service;

import com.byonix.shoplink.api.dto.AuthDtos;
import com.byonix.shoplink.config.MailProperties;
import com.byonix.shoplink.domain.entity.EmailVerificationToken;
import com.byonix.shoplink.domain.entity.PasswordResetToken;
import com.byonix.shoplink.domain.entity.RefreshToken;
import com.byonix.shoplink.domain.entity.User;
import com.byonix.shoplink.domain.enums.OtpPurpose;
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
import com.byonix.shoplink.security.google.GoogleTokenVerifier;
import com.byonix.shoplink.security.login.GenericAuthException;
import com.byonix.shoplink.security.login.SecurityActionException;
import com.byonix.shoplink.security.ratelimit.RateLimitService;
import com.byonix.shoplink.security.request.ClientRequestContext;
import com.byonix.shoplink.service.notification.EmailNotificationService;
import com.byonix.shoplink.service.security.*;
import com.byonix.shoplink.util.PhoneNormalizer;
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
    private final GoogleTokenVerifier googleTokenVerifier;
    private final PasswordHashService passwordHashService;
    private final OtpService otpService;

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

    @Transactional(readOnly = true)
    public boolean isPhoneAvailable(String phone) {
        String phoneDigits = PhoneNormalizer.digitsOnly(phone);
        if (phoneDigits.length() < 7) {
            return false;
        }
        if (userRepository.existsByPhoneDigits(phoneDigits)) {
            return false;
        }
        return true;
    }

    @Transactional
    public AuthDtos.AuthResponse registerByPhone(AuthDtos.RegisterByPhoneRequest request,
                                                 HttpServletRequest http, HttpServletResponse response) {
        ClientRequestContext ctx = ClientRequestContext.from(http);
        rateLimitService.checkRegisterByIp(ctx.ipAddress());

        String phoneDigits = PhoneNormalizer.digitsOnly(request.phone());
        if (phoneDigits.length() < 7) {
            throw new IllegalArgumentException("Invalid phone number");
        }
        if (userRepository.existsByPhoneDigits(phoneDigits)) {
            throw new IllegalArgumentException("Phone already registered");
        }

        String apiPhone = PhoneNormalizer.toApiForm(request.phone());
        String shop = request.shopName() == null ? "" : request.shopName().trim();
        String name = request.fullName() == null ? "" : request.fullName().trim();
        if (name.isEmpty()) {
            name = shop.isEmpty() ? "Store owner" : shop;
        }

        User user = new User();
        user.setFullName(name);
        user.setEmail(null);
        user.setPhone(apiPhone);
        user.setRole(Role.MERCHANT_OWNER);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setPasswordChangedAt(Instant.now());
        user.setEmailVerifiedAt(Instant.now());
        userRepository.save(user);

        otpService.generateAndSend(user, OtpPurpose.SIGNUP);

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
    public AuthDtos.AuthResponse loginByPhone(AuthDtos.LoginByPhoneRequest request, HttpServletRequest http,
                                              HttpServletResponse response) {
        ClientRequestContext ctx = ClientRequestContext.from(http);
        SecureLoginService.LoginOutcome outcome = secureLoginService.authenticateByPhone(
                request.phone(), request.password(), ctx, com.byonix.shoplink.security.login.LoginPortal.MERCHANT);
        return issue(outcome.user(), http, response, outcome.riskScore(), outcome.extraVerificationRequired(),
                RefreshSessionScope.MERCHANT);
    }

    @Transactional
    public AuthDtos.AuthResponse googleLogin(AuthDtos.GoogleLoginRequest request, HttpServletRequest http,
                                             HttpServletResponse response) {
        ClientRequestContext ctx = ClientRequestContext.from(http);
        rateLimitService.checkGoogleLoginByIp(ctx.ipAddress());

        GoogleTokenVerifier.GoogleIdentity identity = googleTokenVerifier.verify(request.idToken());
        String email = normalizeEmail(identity.email());
        rateLimitService.checkGoogleLoginByEmail(email);
        rateLimitService.checkGoogleLoginByEmailAndIp(email, ctx.ipAddress());

        User user = userRepository.findByGoogleSub(identity.sub())
                .orElseGet(() -> linkOrCreateFromGoogle(identity, email));

        return issue(user, http, response, 0, false, RefreshSessionScope.MERCHANT);
    }

    /**
     * No existing google_sub match (handled by the caller) — fall back to matching by verified
     * email. Phone-only merchants (email IS NULL, see registerByPhone above) are structurally
     * excluded from this lookup: findByEmailIgnoreCase can never match a null column. A phone-only
     * merchant who signs in with Google today therefore gets a brand-new, separate account rather
     * than being merged into their phone account. That's a known, accepted limitation until an
     * explicit account-merge feature exists — not a bug to fix here.
     */
    private User linkOrCreateFromGoogle(GoogleTokenVerifier.GoogleIdentity identity, String email) {
        return userRepository.findByEmailIgnoreCase(email)
                .map(existing -> {
                    if (!existing.getRole().isMerchant()) {
                        // Email collides with a customer/admin account on a different portal.
                        // Same isolation rule password login already enforces — reject, don't link.
                        throw new GenericAuthException();
                    }
                    existing.setGoogleSub(identity.sub());
                    if (existing.getEmailVerifiedAt() == null) {
                        existing.setEmailVerifiedAt(Instant.now());
                    }
                    userRepository.save(existing);
                    return existing;
                })
                .orElseGet(() -> createFromGoogle(identity, email));
    }

    private User createFromGoogle(GoogleTokenVerifier.GoogleIdentity identity, String email) {
        User user = new User();
        String name = identity.name() != null && !identity.name().isBlank() ? identity.name().trim() : "Store owner";
        user.setFullName(name);
        user.setEmail(email);
        user.setGoogleSub(identity.sub());
        user.setRole(Role.MERCHANT_OWNER);
        user.setPasswordHash(passwordHashService.generateUnusablePasswordHash());
        user.setPasswordChangedAt(Instant.now());
        user.setEmailVerifiedAt(Instant.now());
        userRepository.save(user);
        return user;
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

    @Transactional
    public void verifyPhone(String phone, String code, HttpServletRequest http) {
        ClientRequestContext ctx = ClientRequestContext.from(http);
        rateLimitService.checkVerifyPhoneByIp(ctx.ipAddress());
        rateLimitService.checkVerifyPhoneByPhone(PhoneNormalizer.digitsOnly(phone));
        User user = otpService.verify(phone, code, OtpPurpose.SIGNUP);
        user.setPhoneVerifiedAt(Instant.now());
        userRepository.save(user);
    }

    @Transactional
    public AuthDtos.AuthActionResponse resendPhoneVerification(String phone, HttpServletRequest http) {
        ClientRequestContext ctx = ClientRequestContext.from(http);
        rateLimitService.checkPhoneResendByIp(ctx.ipAddress());
        String digits = PhoneNormalizer.digitsOnly(phone);
        rateLimitService.checkPhoneResendByPhone(digits);
        userRepository.findByPhoneDigits(digits)
                .filter(u -> u.getPhoneVerifiedAt() == null)
                .filter(User::isActive)
                .ifPresent(user -> otpService.generateAndSend(user, OtpPurpose.SIGNUP));
        return authResponseFactory.actionMessage(
                "If this phone number is registered and not yet verified, we sent a verification code.");
    }

    @Transactional
    public AuthDtos.AuthActionResponse forgotPasswordByPhone(String phone, HttpServletRequest http) {
        ClientRequestContext ctx = ClientRequestContext.from(http);
        rateLimitService.checkForgotPasswordPhoneByIp(ctx.ipAddress());
        String digits = PhoneNormalizer.digitsOnly(phone);
        rateLimitService.checkForgotPasswordPhoneByPhone(digits);
        userRepository.findByPhoneDigits(digits)
                .filter(User::isActive)
                .filter(u -> u.getRole().isMerchant())
                .ifPresent(user -> {
                    otpService.generateAndSend(user, OtpPurpose.PASSWORD_RESET);
                    securityEventService.log(SecurityEventType.PASSWORD_RESET_REQUESTED, SecurityEventSeverity.INFO,
                            user, null, ctx.ipAddress(), ctx.userAgent(), null);
                });
        return authResponseFactory.actionMessage(
                "If this phone number is registered, we sent a password reset code.");
    }

    @Transactional
    public void resetPasswordByPhone(String phone, String code, String newPassword, HttpServletRequest http) {
        ClientRequestContext ctx = ClientRequestContext.from(http);
        rateLimitService.checkResetPasswordPhoneByIp(ctx.ipAddress());
        User user = otpService.verify(phone, code, OtpPurpose.PASSWORD_RESET);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setPasswordChangedAt(Instant.now());
        user.setTokenVersion(user.getTokenVersion() + 1);
        user.setForcePasswordReset(false);
        userRepository.save(user);
        refreshTokenSecurityService.revokeAllSessions(user);
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
