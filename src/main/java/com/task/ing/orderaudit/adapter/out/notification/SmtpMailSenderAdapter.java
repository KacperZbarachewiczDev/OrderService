package com.task.ing.orderaudit.adapter.out.notification;

import com.task.ing.orderaudit.application.port.out.MailSenderPort;
import com.task.ing.orderaudit.application.config.AuditProperties;
import com.task.ing.orderaudit.domain.notification.NotificationMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SmtpMailSenderAdapter implements MailSenderPort {

    private final JavaMailSender mailSender;
    private final AuditProperties properties;

    @Override
    public void send(NotificationMessage message) {
        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(properties.notification().from());
        mail.setTo(message.recipients().toArray(String[]::new));
        mail.setSubject(message.subject());
        mail.setText(message.body());
        try {
            mailSender.send(mail);
        } catch (MailException e) {
            throw new MailDeliveryFailedException("could not deliver '" + message.subject() + "'", e);
        }
    }

    public static class MailDeliveryFailedException extends RuntimeException {
        public MailDeliveryFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
