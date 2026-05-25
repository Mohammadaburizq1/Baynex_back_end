package com.byonix.shoplink.security.ratelimit;

/**
 * Abstraction for distributed rate limiting.
 * TODO: Implement RedisRateLimiter using Spring Data Redis / Lettuce for multi-instance deployments.
 */
public interface RateLimiter {
    /**
     * @return true if the request is allowed, false if rate limit exceeded
     */
    boolean tryConsume(String key, int maxRequests, long windowSeconds);

    default void reset(String key) {
        // optional; no-op for in-memory unless needed
    }
}
