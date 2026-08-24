package com.task.ing.orderaudit.application.port.out;

import com.task.ing.orderaudit.domain.notification.NotificationMessage;

import java.time.Instant;
import java.util.List;

public interface NotificationOutboxPort {

    long enqueue(NotificationMessage message, Long auditRunId, Instant now);

    List<PendingNotification> claimDue(Instant now, int limit);

    void markSent(long id, Instant sentAt);

    void reschedule(long id, String error, Instant nextAttemptAt);

    void markFailed(long id, String error, Instant failedAt);

    record PendingNotification(long id, NotificationMessage message, int attempts) {
    }
}
