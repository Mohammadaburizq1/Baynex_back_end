package com.byonix.shoplink.service.notification;

import com.byonix.shoplink.config.MailProperties;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailNotificationService {
    private final MailProperties mailProperties;

    @Autowired(required = false)
    private JavaMailSender mailSender;

    public boolean isConfigured() {
        return mailProperties.isEnabled() && mailSender != null;
    }

    public void sendPasswordResetEmail(String toEmail, String rawToken) {
        sendPasswordResetEmail(toEmail, rawToken, mailProperties.getPasswordResetPath());
    }

    public void sendPasswordResetEmail(String toEmail, String rawToken, String resetPath) {
        String link = buildLink(resetPath, rawToken);
        String subject = "Reset your khanGates password";
        String text = """
                You requested a password reset for your khanGates account.

                Open this link to choose a new password (valid for a limited time):
                %s

                If you did not request this, you can ignore this email.
                """.formatted(link);
        String html = """
                <p>You requested a password reset for your khanGates account.</p>
                <p><a href="%s">Reset your password</a></p>
                <p>Or copy this link:<br/><code>%s</code></p>
                <p>If you did not request this, you can ignore this email.</p>
                """.formatted(link, link);
        send(toEmail, subject, text, html);
    }

    public void sendEmailVerification(String toEmail, String rawToken) {
        String link = buildLink(mailProperties.getVerifyEmailPath(), rawToken);
        String subject = "Verify your khanGates email";
        String text = """
                Welcome to khanGates.

                Verify your email address:
                %s

                If you did not create an account, you can ignore this email.
                """.formatted(link);
        String html = """
                <p>Welcome to khanGates.</p>
                <p><a href="%s">Verify your email</a></p>
                <p>Or copy this link:<br/><code>%s</code></p>
                """.formatted(link, link);
        send(toEmail, subject, text, html);
    }

    public void sendStaffInvite(String toEmail, String storeName, String rawToken) {
        String link = buildLink(mailProperties.getStaffInvitePath(), rawToken);
        String subject = "You've been invited to join " + storeName + " on khanGates";
        String text = """
                You've been invited to join %s as a team member on khanGates.

                Accept the invite and set your password:
                %s

                If you weren't expecting this, you can ignore this email.
                """.formatted(storeName, link);
        String html = """
                <p>You've been invited to join <strong>%s</strong> as a team member on khanGates.</p>
                <p><a href="%s">Accept the invite</a></p>
                <p>Or copy this link:<br/><code>%s</code></p>
                <p>If you weren't expecting this, you can ignore this email.</p>
                """.formatted(storeName, link, link);
        send(toEmail, subject, text, html);
    }

    private String buildLink(String path, String rawToken) {
        String base = mailProperties.getFrontendBaseUrl().replaceAll("/$", "");
        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        String encoded = URLEncoder.encode(rawToken, StandardCharsets.UTF_8);
        return base + normalizedPath + "?token=" + encoded;
    }

    private void send(String toEmail, String subject, String text, String html) {
        if (!isConfigured()) {
            log.debug("Mail disabled or JavaMailSender not configured; skipped email to {}", toEmail);
            return;
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(mailProperties.getFrom(), mailProperties.getFromName());
            helper.setTo(toEmail);
            helper.setSubject(subject);
            helper.setText(text, html);
            mailSender.send(message);
            log.info("Sent email '{}' to {}", subject, toEmail);
        } catch (Exception e) {
            log.error("Failed to send email '{}' to {}", subject, toEmail, e);
        }
    }
}
