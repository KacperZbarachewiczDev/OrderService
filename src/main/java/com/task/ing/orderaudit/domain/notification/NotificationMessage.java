package com.task.ing.orderaudit.domain.notification;

import java.util.List;
import java.util.Objects;

public record NotificationMessage(List<String> recipients, String subject, String body) {

    public NotificationMessage {
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(body, "body must not be null");
        recipients = recipients == null ? List.of() : List.copyOf(recipients);
        if (recipients.isEmpty()) {
            throw new IllegalArgumentException("a notification needs at least one recipient");
        }
    }
}
