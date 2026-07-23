package com.sks.common;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class MailAlertNotifierTest {

    private static MimeMessage dummyMsg() {
        return new MimeMessage(Session.getInstance(new Properties()));
    }

    /** configured：ObjectProvider 返回 mock sender，host 非空。 */
    private static MailAlertNotifier configured(JavaMailSender mockSender) {
        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mockSender);
        return new MailAlertNotifier(provider, "smtp.example.com", "admin@example.com");
    }

    /** 未 configured：host 空。 */
    private static MailAlertNotifier unconfigured(JavaMailSender mockSender) {
        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mockSender);
        return new MailAlertNotifier(provider, "", "admin@example.com");
    }

    @Test
    void unconfiguredStubDoesNotSend() {
        JavaMailSender mock = mock(JavaMailSender.class);
        assertDoesNotThrow(() -> unconfigured(mock).notify("s", "c"));
        verifyNoInteractions(mock);
    }

    @Test
    void configuredSendsToAdmin() {
        JavaMailSender mock = mock(JavaMailSender.class);
        when(mock.createMimeMessage()).thenReturn(dummyMsg());
        configured(mock).notify("余额告警", "短信余额不足: 50条");
        verify(mock).send(org.mockito.ArgumentMatchers.any(jakarta.mail.internet.MimeMessage.class));
    }

    @Test
    void sendFailureIsSwallowed() {
        JavaMailSender mock = mock(JavaMailSender.class);
        when(mock.createMimeMessage()).thenReturn(dummyMsg());
        doThrow(new RuntimeException("smtp down")).when(mock)
                .send(any(jakarta.mail.internet.MimeMessage.class));
        assertDoesNotThrow(() -> configured(mock).notify("s", "c"));
    }

    @Test
    void noBeanStubDoesNotThrow() {
        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        MailAlertNotifier n = new MailAlertNotifier(provider, "smtp.example.com", "admin@example.com");
        assertDoesNotThrow(() -> n.notify("s", "c"));
    }
}
