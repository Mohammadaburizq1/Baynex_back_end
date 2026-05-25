package com.byonix.shoplink.service.security;

import com.byonix.shoplink.domain.entity.LoginAttempt;
import com.byonix.shoplink.domain.entity.User;
import com.byonix.shoplink.domain.enums.LoginFailureReason;
import com.byonix.shoplink.repository.LoginAttemptRepository;
import com.byonix.shoplink.security.request.ClientRequestContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class LoginAttemptService {
    private final LoginAttemptRepository repository;

    @Transactional
    public LoginAttempt record(String email, User user, ClientRequestContext ctx, boolean success,
                               LoginFailureReason reason, int riskScore) {
        LoginAttempt attempt = new LoginAttempt();
        attempt.setEmail(email);
        attempt.setUserId(user != null ? user.getId() : null);
        attempt.setIpAddress(ctx.ipAddress());
        attempt.setUserAgent(ctx.userAgent());
        attempt.setSuccess(success);
        attempt.setFailureReason(success ? null : reason);
        attempt.setRiskScore(riskScore);
        attempt.setCountry(ctx.country());
        attempt.setCity(ctx.city());
        attempt.setDeviceFingerprint(ctx.deviceFingerprint());
        return repository.save(attempt);
    }

    public boolean tooManyFailuresForEmail(String email) {
        long count = repository.countFailedByEmailSince(email, Instant.now().minusSeconds(15 * 60));
        return count >= 5;
    }

    public boolean tooManyFailuresForIpPerMinute(String ip) {
        return repository.countFailedByIpSince(ip, Instant.now().minusSeconds(60)) >= 10;
    }

    public boolean tooManyFailuresForIpPerHour(String ip) {
        return repository.countFailedByIpSince(ip, Instant.now().minusSeconds(3600)) >= 50;
    }

    public Page<LoginAttempt> all(Pageable pageable) {
        return repository.findAllByOrderByCreatedAtDesc(pageable);
    }

    public Page<LoginAttempt> suspicious(Pageable pageable) {
        return repository.findByRiskScoreGreaterThanEqualOrderByCreatedAtDesc(50, pageable);
    }
}
