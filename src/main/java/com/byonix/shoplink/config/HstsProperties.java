package com.byonix.shoplink.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.security.hsts")
public class HstsProperties {
    private boolean enabled;
    private long maxAgeSeconds = 31536000;
    private boolean includeSubdomains = true;
    private boolean preload;
}
