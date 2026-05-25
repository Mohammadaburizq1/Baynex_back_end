package com.byonix.shoplink.security.ratelimit;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryRateLimiter implements RateLimiter {
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    @Override
    public boolean tryConsume(String key, int maxRequests, long windowSeconds) {
        long now = Instant.now().getEpochSecond();
        Window window = windows.compute(key, (k, old) -> {
            if (old == null || now >= old.resetAt) {
                return new Window(now + windowSeconds, 1);
            }
            old.count++;
            return old;
        });
        return window.count <= maxRequests;
    }

    private static final class Window {
        private final long resetAt;
        private int count;

        private Window(long resetAt, int count) {
            this.resetAt = resetAt;
            this.count = count;
        }
    }
}
