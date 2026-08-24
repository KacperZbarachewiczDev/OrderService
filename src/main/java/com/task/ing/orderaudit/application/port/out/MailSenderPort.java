package com.task.ing.orderaudit.application.port.out;

import com.task.ing.orderaudit.domain.notification.NotificationMessage;

public interface MailSenderPort {

    void send(NotificationMessage message);
}
