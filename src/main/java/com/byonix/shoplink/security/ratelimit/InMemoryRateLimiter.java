package com.byonix.shoplink.security.ratelimit;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryRateLimiter implements RateLimiter {
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private long nextCleanup;

    @Override
    public synchronized boolean tryConsume(String key, int maxRequests, long windowSeconds) {
        if (maxRequests < 1 || windowSeconds < 1) throw new IllegalArgumentException("Invalid rate limit");
        long now = Instant.now().getEpochSecond();
        if (now >= nextCleanup) {
            windows.values().removeIf(w -> now >= w.resetAt);
            nextCleanup = now + 60;
        }
        if (!windows.containsKey(key) && windows.size() >= 100_000) return false;
        Window window = windows.compute(key, (k, old) -> {
            if (old == null || now >= old.resetAt) {
                return new Window(now + windowSeconds, 1);
            }
            if (old.count <= maxRequests) old.count++;
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
