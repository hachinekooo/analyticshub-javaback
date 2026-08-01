package com.github.analyticshub.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class EmailServiceTest {

    private JavaMailSender mailSender;
    private EmailService emailService;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("mailSender", mailSender);
        emailService = new EmailService(beanFactory.getBeanProvider(JavaMailSender.class));
        ReflectionTestUtils.setField(emailService, "fromEmail", "sender@example.com");
        ReflectionTestUtils.setField(emailService, "emailEnabled", true);
    }

    @Test
    void returnsSentWhenMailSenderAcceptsMessage() {
        EmailDeliveryStatus status = emailService.sendPrivacyUserNotification(
                "recipient@example.com",
                "subject",
                "content"
        );

        assertThat(status).isEqualTo(EmailDeliveryStatus.SENT);
        verify(mailSender).send(any(SimpleMailMessage.class));
    }

    @Test
    void returnsFailedWhenMailSenderThrows() {
        doThrow(new MailSendException("SMTP delivery failed"))
                .when(mailSender).send(any(SimpleMailMessage.class));

        EmailDeliveryStatus status = emailService.sendPrivacyUserNotification(
                "recipient@example.com",
                "subject",
                "content"
        );

        assertThat(status).isEqualTo(EmailDeliveryStatus.FAILED);
    }

    @Test
    void returnsDisabledWithoutAttemptingDelivery() {
        ReflectionTestUtils.setField(emailService, "emailEnabled", false);

        EmailDeliveryStatus status = emailService.sendPrivacyUserNotification(
                "recipient@example.com",
                "subject",
                "content"
        );

        assertThat(status).isEqualTo(EmailDeliveryStatus.DISABLED);
        verifyNoInteractions(mailSender);
    }

    @Test
    void rejectsInvalidRecipientBeforeDelivery() {
        EmailDeliveryStatus status = emailService.sendPrivacyUserNotification(
                "recipient@example.com\r\nBcc: attacker@example.com",
                "subject",
                "content"
        );

        assertThat(status).isEqualTo(EmailDeliveryStatus.INVALID_RECIPIENT);
        verifyNoInteractions(mailSender);
    }
}
