package com.byonix.shoplink.security.ratelimit;

import com.byonix.shoplink.security.RateLimitFilter.RateLimitExceededException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GoogleLoginRateLimitTest {
    @Test
    void googleLoginBlockedAfterMaxByIp() {
        RateLimitService service = new RateLimitService(new InMemoryRateLimiter());
        for (int i = 0; i < 30; i++) {
            assertDoesNotThrow(() -> service.checkGoogleLoginByIp("203.0.113.30"));
        }
        assertThrows(RateLimitExceededException.class, () -> service.checkGoogleLoginByIp("203.0.113.30"));
    }

    @Test
    void googleLoginBlockedAfterMaxByEmail() {
        RateLimitService service = new RateLimitService(new InMemoryRateLimiter());
        for (int i = 0; i < 20; i++) {
            assertDoesNotThrow(() -> service.checkGoogleLoginByEmail("merchant@example.com"));
        }
        assertThrows(RateLimitExceededException.class, () -> service.checkGoogleLoginByEmail("merchant@example.com"));
    }
}
