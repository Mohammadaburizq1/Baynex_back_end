package com.byonix.shoplink.service.security;

import com.byonix.shoplink.config.LoginSecurityProperties;
import com.byonix.shoplink.util.PhoneNormalizer;
import com.byonix.shoplink.domain.entity.User;
import com.byonix.shoplink.domain.enums.LoginFailureReason;
import com.byonix.shoplink.domain.enums.Role;
import com.byonix.shoplink.domain.enums.SecurityEventSeverity;
import com.byonix.shoplink.domain.enums.SecurityEventType;
import com.byonix.shoplink.repository.UserRepository;
import com.byonix.shoplink.security.login.GenericAuthException;
import com.byonix.shoplink.security.login.LoginPortal;
import com.byonix.shoplink.security.ratelimit.RateLimitService;
import com.byonix.shoplink.security.request.ClientRequestContext;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class SecureLoginService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RateLimitService rateLimitService;
    private final IpBlocklistService ipBlocklistService;
    private final AccountLockService accountLockService;
    private final LoginAttemptService loginAttemptService;
    private final RiskAssessmentService riskAssessmentService;
    private final SecurityEventService securityEventService;
    private final SecurityAlertService securityAlertService;
    private final LoginSecurityProperties props;

    @Value("${app.auth.require-email-verification-for-login:false}")
    private boolean requireEmailVerificationForLogin;

    public record LoginOutcome(User user, int riskScore, boolean extraVerificationRequired) {}

    @Transactional
    public LoginOutcome authenticate(String email, String password, ClientRequestContext ctx, LoginPortal portal) {
        rateLimitService.checkLoginByIp(ctx.ipAddress());
        rateLimitService.checkLoginByEmail(email);
        rateLimitService.checkLoginByEmailAndIp(email, ctx.ipAddress());

        if (ipBlocklistService.isBlocked(ctx.ipAddress())) {
            fail(email, null, ctx, LoginFailureReason.IP_BLOCKED, portal, true);
            throw new GenericAuthException();
        }

        if (loginAttemptService.tooManyFailuresForIpPerMinute(ctx.ipAddress())
                || loginAttemptService.tooManyFailuresForIpPerHour(ctx.ipAddress())
                || loginAttemptService.tooManyFailuresForEmail(email)) {
            fail(email, null, ctx, LoginFailureReason.RATE_LIMITED, portal, false);
            throw new GenericAuthException();
        }

        Optional<User> userOpt = userRepository.findByEmailIgnoreCase(email);
        User user = userOpt.orElse(null);

        applyProgressiveDelay(user);

        if (user == null) {
            fail(email, null, ctx, LoginFailureReason.INVALID_CREDENTIALS, portal, true);
            throw new GenericAuthException();
        }

        if (!roleAllowedOnPortal(user.getRole(), portal)) {
            fail(email, user, ctx, LoginFailureReason.INVALID_CREDENTIALS, portal, true);
            throw new GenericAuthException();
        }

        if (!user.isActive()) {
            fail(email, user, ctx, LoginFailureReason.ACCOUNT_DISABLED, portal, false);
            throw new GenericAuthException();
        }

        if (accountLockService.isLocked(user)) {
            fail(email, user, ctx, LoginFailureReason.ACCOUNT_LOCKED, portal, false);
            throw new GenericAuthException();
        }

        if (user.isForcePasswordReset()) {
            fail(email, user, ctx, LoginFailureReason.FORCE_PASSWORD_RESET, portal, false);
            throw new GenericAuthException();
        }

        boolean wrongPassword = !passwordEncoder.matches(password, user.getPasswordHash());
        int risk = riskAssessmentService.calculateLoginRisk(user, email, ctx, wrongPassword, portal.isAdmin(), false);

        if (riskAssessmentService.shouldBlockLogin(risk)) {
            fail(email, user, ctx, LoginFailureReason.HIGH_RISK_BLOCKED, portal, wrongPassword);
            user.setSuspiciousActivityFlag(true);
            userRepository.save(user);
            throw new GenericAuthException();
        }

        if (wrongPassword) {
            accountLockService.recordFailedLogin(user, ctx.ipAddress(), ctx.userAgent());
            fail(email, user, ctx, LoginFailureReason.INVALID_CREDENTIALS, portal, true);
            throw new GenericAuthException();
        }

        if (requireEmailVerificationForLogin && user.getEmailVerifiedAt() == null) {
            fail(email, user, ctx, LoginFailureReason.EMAIL_NOT_VERIFIED, portal, false);
            throw new GenericAuthException();
        }

        boolean extraVerification = riskAssessmentService.requiresExtraVerification(risk);
        if (extraVerification) {
            user.setSuspiciousActivityFlag(true);
            userRepository.save(user);
            securityEventService.log(SecurityEventType.SUSPICIOUS_LOGIN, SecurityEventSeverity.HIGH, user, null,
                    ctx.ipAddress(), ctx.userAgent(), Map.of("riskScore", risk));
        }

        if (riskAssessmentService.shouldAlert(risk)) {
            securityAlertService.sendSecurityAlert(
                    portal.isAdmin() ? SecurityEventType.ADMIN_LOGIN_FAILED : SecurityEventType.SUSPICIOUS_LOGIN,
                    user, email, risk, Map.of("ip", ctx.ipAddress()));
        }

        boolean newDevice = user.getLastLoginIp() != null
                && !user.getLastLoginIp().equals(ctx.ipAddress());
        accountLockService.recordSuccessfulLogin(user, ctx.ipAddress(), ctx.userAgent());
        loginAttemptService.record(email, user, ctx, true, null, risk);
        securityEventService.log(
                portal.isAdmin() ? SecurityEventType.ADMIN_LOGIN_SUCCESS : SecurityEventType.LOGIN_SUCCESS,
                SecurityEventSeverity.INFO, user, null, ctx.ipAddress(), ctx.userAgent(),
                Map.of("riskScore", risk));
        if (newDevice) {
            securityEventService.log(SecurityEventType.NEW_DEVICE_LOGIN, SecurityEventSeverity.WARN, user, null,
                    ctx.ipAddress(), ctx.userAgent(), null);
        }

        return new LoginOutcome(user, risk, extraVerification);
    }

    @Transactional
    public LoginOutcome authenticateByPhone(String phone, String password, ClientRequestContext ctx, LoginPortal portal) {
        rateLimitService.checkLoginByIp(ctx.ipAddress());
        rateLimitService.checkLoginByEmail(phone);
        rateLimitService.checkLoginByEmailAndIp(phone, ctx.ipAddress());

        if (ipBlocklistService.isBlocked(ctx.ipAddress())) {
            fail(phone, null, ctx, LoginFailureReason.IP_BLOCKED, portal, true);
            throw new GenericAuthException();
        }

        if (loginAttemptService.tooManyFailuresForIpPerMinute(ctx.ipAddress())
                || loginAttemptService.tooManyFailuresForIpPerHour(ctx.ipAddress())
                || loginAttemptService.tooManyFailuresForEmail(phone)) {
            fail(phone, null, ctx, LoginFailureReason.RATE_LIMITED, portal, false);
            throw new GenericAuthException();
        }

        String digits = PhoneNormalizer.digitsOnly(phone);
        Optional<User> userOpt = userRepository.findByPhoneDigits(digits);
        User user = userOpt.orElse(null);

        applyProgressiveDelay(user);

        if (user == null) {
            fail(phone, null, ctx, LoginFailureReason.INVALID_CREDENTIALS, portal, true);
            throw new GenericAuthException();
        }

        if (!roleAllowedOnPortal(user.getRole(), portal)) {
            fail(phone, user, ctx, LoginFailureReason.INVALID_CREDENTIALS, portal, true);
            throw new GenericAuthException();
        }

        if (!user.isActive()) {
            fail(phone, user, ctx, LoginFailureReason.ACCOUNT_DISABLED, portal, false);
            throw new GenericAuthException();
        }

        if (accountLockService.isLocked(user)) {
            fail(phone, user, ctx, LoginFailureReason.ACCOUNT_LOCKED, portal, false);
            throw new GenericAuthException();
        }

        if (user.isForcePasswordReset()) {
            fail(phone, user, ctx, LoginFailureReason.FORCE_PASSWORD_RESET, portal, false);
            throw new GenericAuthException();
        }

        boolean wrongPassword = !passwordEncoder.matches(password, user.getPasswordHash());
        int risk = riskAssessmentService.calculateLoginRisk(user, phone, ctx, wrongPassword, portal.isAdmin(), false);

        if (riskAssessmentService.shouldBlockLogin(risk)) {
            fail(phone, user, ctx, LoginFailureReason.HIGH_RISK_BLOCKED, portal, wrongPassword);
            user.setSuspiciousActivityFlag(true);
            userRepository.save(user);
            throw new GenericAuthException();
        }

        if (wrongPassword) {
            accountLockService.recordFailedLogin(user, ctx.ipAddress(), ctx.userAgent());
            fail(phone, user, ctx, LoginFailureReason.INVALID_CREDENTIALS, portal, true);
            throw new GenericAuthException();
        }

        boolean extraVerification = riskAssessmentService.requiresExtraVerification(risk);
        if (extraVerification) {
            user.setSuspiciousActivityFlag(true);
            userRepository.save(user);
            securityEventService.log(SecurityEventType.SUSPICIOUS_LOGIN, SecurityEventSeverity.HIGH, user, null,
                    ctx.ipAddress(), ctx.userAgent(), Map.of("riskScore", risk));
        }

        boolean newDevice = user.getLastLoginIp() != null && !user.getLastLoginIp().equals(ctx.ipAddress());
        accountLockService.recordSuccessfulLogin(user, ctx.ipAddress(), ctx.userAgent());
        loginAttemptService.record(phone, user, ctx, true, null, risk);
        securityEventService.log(SecurityEventType.LOGIN_SUCCESS, SecurityEventSeverity.INFO, user, null,
                ctx.ipAddress(), ctx.userAgent(), Map.of("riskScore", risk));
        if (newDevice) {
            securityEventService.log(SecurityEventType.NEW_DEVICE_LOGIN, SecurityEventSeverity.WARN, user, null,
                    ctx.ipAddress(), ctx.userAgent(), null);
        }

        return new LoginOutcome(user, risk, extraVerification);
    }

    private void fail(String email, User user, ClientRequestContext ctx, LoginFailureReason reason,
                      LoginPortal portal, boolean wrongPassword) {
        int risk = riskAssessmentService.calculateLoginRisk(user, email, ctx, wrongPassword, portal.isAdmin(), false);
        loginAttemptService.record(email, user, ctx, false, reason, risk);
        SecurityEventType type = portal.isAdmin() ? SecurityEventType.ADMIN_LOGIN_FAILED : SecurityEventType.LOGIN_FAILED;
        securityEventService.log(type, SecurityEventSeverity.WARN, user, email, ctx.ipAddress(), ctx.userAgent(),
                Map.of("reason", reason.name(), "riskScore", risk));
        if (portal.isAdmin()) {
            securityAlertService.sendAdminLoginFailureAlert(email, ctx.ipAddress());
        }
    }

    private static boolean roleAllowedOnPortal(Role role, LoginPortal portal) {
        return switch (portal) {
            case ADMIN -> role.isAdmin();
            case MERCHANT -> role.isMerchant();
            case CUSTOMER -> role.isCustomer();
        };
    }

    private void applyProgressiveDelay(User user) {
        int failures = user != null ? user.getFailedLoginCount() : 0;
        long delayMs = Math.min(5000L, failures * 500L);
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
