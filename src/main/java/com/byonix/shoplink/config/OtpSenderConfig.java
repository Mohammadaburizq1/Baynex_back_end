package com.byonix.shoplink.config;

import com.byonix.shoplink.service.security.LoggingOtpSender;
import com.byonix.shoplink.service.security.OtpSender;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OtpSenderConfig {
    @Bean
    @ConditionalOnMissingBean(OtpSender.class)
    OtpSender loggingOtpSender(AuthProperties authProperties) {
        return new LoggingOtpSender(authProperties);
    }
}
