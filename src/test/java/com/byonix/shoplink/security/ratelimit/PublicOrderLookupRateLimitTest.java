package com.byonix.shoplink.security.ratelimit;

import com.byonix.shoplink.security.RateLimitFilter.RateLimitExceededException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PublicOrderLookupRateLimitTest {
    @Test
    void normalizedCodeBudgetIsSharedAcrossClientAddresses() {
        RateLimitService service = new RateLimitService(new InMemoryRateLimiter());
        for (int i = 0; i < 10; i++) service.checkPublicOrderLookupByCode("store", "abc123");
        assertThrows(RateLimitExceededException.class, () -> service.checkPublicOrderLookupByCode("store", " ABC123 "));
        assertDoesNotThrow(() -> service.checkPublicOrderLookupByCode("other-store", "ABC123"));
    }
    @Test
    void orderLookupRateLimited() {
        RateLimitService service = new RateLimitService(new InMemoryRateLimiter());
        for (int i = 0; i < 20; i++) {
            assertDoesNotThrow(() -> service.checkPublicOrderLookupByIp("203.0.113.1"));
        }
        assertThrows(RateLimitExceededException.class, () -> service.checkPublicOrderLookupByIp("203.0.113.1"));
    }
}
