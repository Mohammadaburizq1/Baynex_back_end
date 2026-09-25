package com.byonix.shoplink.service.security;

import com.byonix.shoplink.domain.entity.User;
import com.byonix.shoplink.domain.enums.SecurityEventType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Placeholder for outbound security notifications.
 * TODO: Email/SMS security alerts via SendGrid, Twilio, etc.
 * TODO: Integrate with SIEM / log monitoring (Datadog, Splunk, WAF events).
 */
@Slf4j
@Service
public class SecurityAlertService {
    public void sendSecurityAlert(SecurityEventType type, User user, String email, int riskScore, Map<String, Object> context) {
        log.warn("SECURITY_ALERT type={} user={} riskScore={}",
                type, user != null ? user.getId() : null, riskScore);
    }

    public void sendAdminLoginFailureAlert(String email, String ip) {
        log.warn("ADMIN_LOGIN_FAILED");
    }
}
