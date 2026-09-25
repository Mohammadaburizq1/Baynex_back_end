package com.byonix.shoplink.service;

import com.byonix.shoplink.config.MailProperties;
import com.byonix.shoplink.service.notification.EmailNotificationService;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmailReliabilityTest {
    @Test void disabledIsExplicit() {
        assertEquals(EmailNotificationService.DeliveryResult.DISABLED,
                new EmailNotificationService(new MailProperties()).sendEmailVerification("test@example.org", "token"));
    }
    @Test void failuresAreBoundedAndReported() {
        var props = new MailProperties(); props.setEnabled(true);
        var sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenAnswer(i -> new MimeMessage((Session) null));
        doThrow(new MailSendException("sensitive provider diagnostic")).when(sender).send(any(MimeMessage.class));
        var service = new EmailNotificationService(props);
        ReflectionTestUtils.setField(service, "mailSender", sender);
        assertEquals(EmailNotificationService.DeliveryResult.FAILED, service.sendEmailVerification("test@example.org", "secret"));
        verify(sender, times(3)).send(any(MimeMessage.class));
    }
    @Test void transientFailureCanRecover() {
        var props = new MailProperties(); props.setEnabled(true);
        var sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenAnswer(i -> new MimeMessage((Session) null));
        doThrow(new MailSendException("temporary")).doNothing().when(sender).send(any(MimeMessage.class));
        var service = new EmailNotificationService(props);
        ReflectionTestUtils.setField(service, "mailSender", sender);
        assertEquals(EmailNotificationService.DeliveryResult.ACCEPTED_BY_SMTP, service.sendEmailVerification("test@example.org", "secret"));
        verify(sender, times(2)).send(any(MimeMessage.class));
    }
}
