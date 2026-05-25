package com.byonix.shoplink.service;

import com.byonix.shoplink.api.dto.AuthDtos;
import com.byonix.shoplink.api.dto.SecurityDtos;
import com.byonix.shoplink.domain.entity.User;
import com.byonix.shoplink.repository.UserRepository;
import com.byonix.shoplink.security.RefreshTokenCookieService;
import com.byonix.shoplink.security.request.ClientRequestContext;
import com.byonix.shoplink.security.ratelimit.RateLimitService;
import com.byonix.shoplink.domain.enums.RefreshSessionScope;
import com.byonix.shoplink.security.login.LoginPortal;
import com.byonix.shoplink.service.security.MfaChallengeService;
import com.byonix.shoplink.service.security.SecureLoginService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminAuthService {
    private final AuthService authService;
    private final SecureLoginService secureLoginService;
    private final MfaChallengeService mfaChallengeService;
    private final RateLimitService rateLimitService;
    private final MapperService mapper;
    private final RefreshTokenCookieService refreshTokenCookieService;

    @Transactional
    public SecurityDtos.AdminLoginResponse adminLogin(SecurityDtos.AdminLoginRequest request,
                                                      HttpServletRequest http, HttpServletResponse response) {
        ClientRequestContext ctx = ClientRequestContext.from(http);
        rateLimitService.checkAdminLoginByIp(ctx.ipAddress());
        String email = request.email().trim().toLowerCase();
        SecureLoginService.LoginOutcome outcome = secureLoginService.authenticate(email, request.password(), ctx, LoginPortal.ADMIN);
        User user = outcome.user();

        if (mfaChallengeService.requiresMfaBeforeTokens(user)) {
            return SecurityDtos.AdminLoginResponse.mfaChallenge(mfaChallengeService.createChallenge(user));
        }

        AuthDtos.AuthResponse auth = authService.issue(user, http, response, outcome.riskScore(),
                outcome.extraVerificationRequired(), RefreshSessionScope.ADMIN);
        return SecurityDtos.AdminLoginResponse.authenticated(auth);
    }

    @Transactional
    public AuthDtos.AuthResponse verifyMfa(SecurityDtos.MfaVerifyRequest request, HttpServletRequest http,
                                           HttpServletResponse response) {
        ClientRequestContext ctx = ClientRequestContext.from(http);
        User user = mfaChallengeService.verifyChallengeAndCode(request.mfaChallengeToken(), request.mfaCode(),
                ctx.ipAddress(), ctx.userAgent());
        return authService.issue(user, http, response, 0, false, RefreshSessionScope.ADMIN);
    }

    @Transactional
    public AuthDtos.AuthResponse adminRefresh(AuthDtos.RefreshRequest request, HttpServletRequest http,
                                              HttpServletResponse response) {
        ClientRequestContext ctx = ClientRequestContext.from(http);
        rateLimitService.checkRefreshByIp(ctx.ipAddress());
        return authService.refresh(request, http, response, RefreshSessionScope.ADMIN);
    }

    @Transactional
    public void adminLogout(AuthDtos.LogoutRequest request, HttpServletRequest http, HttpServletResponse response) {
        authService.logout(request, http, response, RefreshSessionScope.ADMIN);
    }

    public AuthDtos.UserResponse adminMe(User user) {
        return mapper.user(user);
    }

    @Transactional
    public AuthDtos.AuthActionResponse forgotPassword(String email, HttpServletRequest http) {
        return authService.forgotAdminPassword(email, http);
    }

    @Transactional
    public void resetPassword(String rawToken, String newPassword, HttpServletRequest http) {
        authService.resetAdminPassword(rawToken, newPassword, http);
    }
}
