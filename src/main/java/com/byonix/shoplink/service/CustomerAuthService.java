package com.byonix.shoplink.service;

import com.byonix.shoplink.api.dto.AuthDtos;
import com.byonix.shoplink.domain.entity.EmailVerificationToken;
import com.byonix.shoplink.domain.entity.User;
import com.byonix.shoplink.domain.enums.RefreshSessionScope;
import com.byonix.shoplink.domain.enums.Role;
import com.byonix.shoplink.domain.enums.SecurityEventSeverity;
import com.byonix.shoplink.domain.enums.SecurityEventType;
import com.byonix.shoplink.repository.EmailVerificationTokenRepository;
import com.byonix.shoplink.repository.UserRepository;
import com.byonix.shoplink.security.login.LoginPortal;
import com.byonix.shoplink.security.ratelimit.RateLimitService;
import com.byonix.shoplink.security.request.ClientRequestContext;
import com.byonix.shoplink.service.notification.EmailNotificationService;
import com.byonix.shoplink.service.security.DevTokenLogger;
import com.byonix.shoplink.service.security.SecureLoginService;
import com.byonix.shoplink.service.security.SecurityEventService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CustomerAuthService {
    private final UserRepository userRepository;
    private final EmailVerificationTokenRepository emailVerificationTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecureLoginService secureLoginService;
    private final AuthService authService;
    private final RateLimitService rateLimitService;
    private final TokenHashService tokenHashService;
    private final DevTokenLogger devTokenLogger;
    private final EmailNotificationService emailNotificationService;
    private final SecurityEventService securityEventService;

    @org.springframework.beans.factory.annotation.Value("${app.auth.email-verification-expiration-hours:48}")
    private long emailVerificationHours;

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
        user.setRole(Role.CUSTOMER);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setPasswordChangedAt(Instant.now());
        userRepository.save(user);

        queueEmailVerification(user, email);
        securityEventService.log(SecurityEventType.REGISTER_SUCCESS, SecurityEventSeverity.INFO,
                user, null, ctx.ipAddress(), ctx.userAgent(), null);

        return authService.issue(user, http, response, 0, false, RefreshSessionScope.CUSTOMER);
    }

    @Transactional
    public AuthDtos.AuthResponse login(AuthDtos.LoginRequest request, HttpServletRequest http,
                                       HttpServletResponse response) {
        ClientRequestContext ctx = ClientRequestContext.from(http);
        String email = normalizeEmail(request.email());
        SecureLoginService.LoginOutcome outcome = secureLoginService.authenticate(
                email, request.password(), ctx, LoginPortal.CUSTOMER);
        return authService.issue(outcome.user(), http, response, outcome.riskScore(),
                outcome.extraVerificationRequired(), RefreshSessionScope.CUSTOMER);
    }

    @Transactional(noRollbackFor = com.byonix.shoplink.security.login.GenericAuthException.class)
    public AuthDtos.AuthResponse refresh(AuthDtos.RefreshRequest request, HttpServletRequest http,
                                         HttpServletResponse response) {
        return authService.refresh(request, http, response, RefreshSessionScope.CUSTOMER);
    }

    @Transactional
    public void logout(AuthDtos.LogoutRequest request, HttpServletRequest http, HttpServletResponse response) {
        authService.logout(request, http, response, RefreshSessionScope.CUSTOMER);
    }

    private void queueEmailVerification(User user, String email) {
        emailVerificationTokenRepository.deleteByUserId(user.getId());
        String verifyRaw = tokenHashService.randomRefreshToken();
        EmailVerificationToken evt = new EmailVerificationToken();
        evt.setUser(user);
        evt.setTokenHash(tokenHashService.hash(verifyRaw));
        evt.setExpiresAt(Instant.now().plusSeconds(emailVerificationHours * 3600));
        emailVerificationTokenRepository.save(evt);
        devTokenLogger.logEmailVerificationToken(email, verifyRaw);
        emailNotificationService.sendEmailVerification(email, verifyRaw);
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
