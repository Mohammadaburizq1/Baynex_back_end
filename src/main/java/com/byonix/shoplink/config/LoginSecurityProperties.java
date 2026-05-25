package com.byonix.shoplink.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.security.login")
public class LoginSecurityProperties {
    private int merchantLockThreshold1 = 5;
    private int merchantLockThreshold2 = 10;
    private int merchantLockThreshold3 = 20;
    private int adminLockThreshold1 = 3;
    private int adminLockThreshold2 = 5;
    private int adminLockThreshold3 = 10;
    private int lockMinutes1 = 15;
    private int lockMinutes2 = 60;
    private int maxFailedPerEmailWindow = 5;
    private int emailFailureWindowMinutes = 15;
    private int maxFailedPerIpPerMinute = 10;
    private int maxFailedPerIpPerHour = 50;
    private int riskExtraVerification = 50;
    private int riskSecurityAlert = 80;
    private int riskBlockLogin = 100;
    private boolean mfaDevBypass = false;
}
