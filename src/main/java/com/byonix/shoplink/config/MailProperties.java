package com.byonix.shoplink.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.mail")
public class MailProperties {
    /**
     * When true and spring.mail.host is configured, transactional emails are sent.
     */
    private boolean enabled;

    private String from = "noreply@khanGates.app";

    private String fromName = "khanGates";

    /**
     * Flutter/web app base URL for links in emails (no trailing slash).
     * Example: http://localhost:8080
     */
    private String frontendBaseUrl = "http://localhost:8080";

    private String passwordResetPath = "/reset-password";

    private String adminPasswordResetPath = "/admin/reset-password";

    private String verifyEmailPath = "/verify-email";

    private String staffInvitePath = "/staff/accept-invite";
}
