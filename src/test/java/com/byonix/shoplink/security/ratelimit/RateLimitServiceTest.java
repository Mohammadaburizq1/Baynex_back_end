package com.byonix.shoplink.security.ratelimit;

import com.byonix.shoplink.security.RateLimitFilter.RateLimitExceededException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RateLimitServiceTest {
    @Test
    void rateLimitBlocksAfterMax() {
        RateLimiter limiter = new InMemoryRateLimiter();
        RateLimitService service = new RateLimitService(limiter);
        for (int i = 0; i < 5; i++) {
            assertDoesNotThrow(() -> service.checkRegisterByIp("1.2.3.4"));
        }
        assertThrows(RateLimitExceededException.class, () -> service.checkRegisterByIp("1.2.3.4"));
    }
}
