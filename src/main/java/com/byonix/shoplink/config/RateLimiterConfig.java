package com.byonix.shoplink.config;

import com.byonix.shoplink.security.ratelimit.InMemoryRateLimiter;
import com.byonix.shoplink.security.ratelimit.RateLimiter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RateLimiterConfig {
    @Bean
    @ConditionalOnMissingBean(RateLimiter.class)
    RateLimiter inMemoryRateLimiter(@org.springframework.beans.factory.annotation.Value("${app.security.rate-limit.backend:memory}") String backend) {
        if (!"memory".equals(backend)) throw new IllegalStateException("Unsupported rate-limit backend; only memory is implemented (single instance required)");
        return new InMemoryRateLimiter();
    }
}
