package com.byonix.shoplink.security.ratelimit;

import com.byonix.shoplink.security.RateLimitFilter.RateLimitExceededException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RateLimitService {
    private final RateLimiter rateLimiter;

    public void checkOrThrow(String bucket, String dimension, int maxRequests, long windowSeconds) {
        String key = bucket + ":" + dimension;
        if (!rateLimiter.tryConsume(key, maxRequests, windowSeconds)) {
            throw new RateLimitExceededException();
        }
    }

    public void checkLoginByIp(String ip) {
        checkOrThrow("login-ip-1m", ip, 30, 60);
        checkOrThrow("login-ip-1h", ip, 100, 3600);
    }

    public void checkLoginByEmail(String email) {
        checkOrThrow("login-email", email, 20, 900);
    }

    public void checkLoginByEmailAndIp(String email, String ip) {
        checkOrThrow("login-email-ip", email + "|" + ip, 15, 900);
    }

    public void checkRegisterByIp(String ip) {
        checkOrThrow("register-ip", ip, 5, 300);
    }

    public void checkForgotPasswordByIp(String ip) {
        checkOrThrow("forgot-ip", ip, 5, 3600);
    }

    public void checkForgotPasswordByEmail(String email) {
        checkOrThrow("forgot-email", email, 3, 3600);
    }

    public void checkResetPasswordByIp(String ip) {
        checkOrThrow("reset-ip", ip, 15, 3600);
    }

    public void checkVerifyResendByIp(String ip) {
        checkOrThrow("verify-resend-ip", ip, 10, 3600);
    }

    public void checkVerifyResendByEmail(String email) {
        checkOrThrow("verify-resend-email", email, 5, 3600);
    }

    public void checkRefreshByIp(String ip) {
        checkOrThrow("refresh-ip", ip, 60, 60);
    }

    public void checkAdminLoginByIp(String ip) {
        checkOrThrow("admin-login-ip", ip, 10, 60);
    }

    public void checkPublicOrderLookupByIp(String ip) {
        checkOrThrow("order-lookup-ip", ip, 20, 300);
    }
}
