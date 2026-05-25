package com.byonix.shoplink.service.security;

import com.byonix.shoplink.config.LoginSecurityProperties;
import com.byonix.shoplink.domain.entity.User;
import com.byonix.shoplink.domain.enums.Role;
import com.byonix.shoplink.repository.LoginAttemptRepository;
import com.byonix.shoplink.security.request.ClientRequestContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class RiskAssessmentService {
    private final LoginAttemptRepository loginAttemptRepository;
    private final LoginSecurityProperties props;
    private final IpBlocklistService ipBlocklistService;

    public int calculateLoginRisk(User user, String email, ClientRequestContext ctx, boolean wrongPassword,
                                boolean adminLoginAttempt, boolean refreshReuse) {
        int score = 0;
        if (wrongPassword) score += 20;
        if (user != null && isNewIp(user, ctx.ipAddress())) score += 20;
        if (user != null && isNewUserAgent(user, ctx.userAgent())) score += 20;
        if (loginAttemptRepository.countFailedByIpSince(ctx.ipAddress(), Instant.now().minusSeconds(60)) >= 5) {
            score += 30;
        }
        if (loginAttemptRepository.countDistinctEmailsFailedByIpSince(ctx.ipAddress(), Instant.now().minusSeconds(3600)) >= 5) {
            score += 40;
        }
        if (adminLoginAttempt) score += 40;
        if (ipBlocklistService.isBlocked(ctx.ipAddress())) score += 100;
        if (refreshReuse) score += 100;
        return score;
    }

    public boolean requiresExtraVerification(int riskScore) {
        return riskScore >= props.getRiskExtraVerification() && riskScore < props.getRiskBlockLogin();
    }

    public boolean shouldBlockLogin(int riskScore) {
        return riskScore >= props.getRiskBlockLogin();
    }

    public boolean shouldAlert(int riskScore) {
        return riskScore >= props.getRiskSecurityAlert();
    }

    private boolean isNewIp(User user, String ip) {
        return user.getLastLoginIp() != null && !user.getLastLoginIp().equals(ip);
    }

    private boolean isNewUserAgent(User user, String ua) {
        return user.getLastLoginUserAgent() != null && ua != null
                && !Objects.equals(user.getLastLoginUserAgent(), ua);
    }
}
