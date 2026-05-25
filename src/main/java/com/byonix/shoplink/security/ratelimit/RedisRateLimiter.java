package com.byonix.shoplink.security.ratelimit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * TODO: Implement Redis-backed sliding window / token bucket rate limiting.
 * Enable with app.security.rate-limit.backend=redis and spring.data.redis.* configuration.
 * TODO: Wire Web Application Firewall (WAF) signals into block decisions.
 */
@Component
@ConditionalOnProperty(name = "app.security.rate-limit.backend", havingValue = "redis")
public class RedisRateLimiter implements RateLimiter {
    @Override
    public boolean tryConsume(String key, int maxRequests, long windowSeconds) {
        throw new UnsupportedOperationException("Redis rate limiter not implemented yet");
    }
}
