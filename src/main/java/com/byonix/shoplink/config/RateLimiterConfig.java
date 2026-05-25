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
    RateLimiter inMemoryRateLimiter() {
        return new InMemoryRateLimiter();
    }
}
