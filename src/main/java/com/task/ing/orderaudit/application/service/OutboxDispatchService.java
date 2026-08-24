package com.task.ing.orderaudit.application.service;

import com.task.ing.orderaudit.application.port.in.DispatchNotificationsUseCase;
import com.task.ing.orderaudit.application.port.out.MailSenderPort;
import com.task.ing.orderaudit.application.port.out.NotificationOutboxPort;
import com.task.ing.orderaudit.application.config.AuditProperties;
import com.task.ing.orderaudit.domain.outbox.OutboxRetryPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class OutboxDispatchService implements DispatchNotificationsUseCase {

    private final NotificationOutboxPort outbox;
    private final MailSenderPort mailSender;
    private final OutboxRetryPolicy retryPolicy;
    private final AuditProperties properties;
    private final Clock clock;

    @Override
    public int dispatchDue() {
        List<NotificationOutboxPort.PendingNotification> due =
                outbox.claimDue(clock.instant(), properties.outbox().batchSize());
        int delivered = 0;
        for (NotificationOutboxPort.PendingNotification pending : due) {
            delivered += deliver(pending) ? 1 : 0;
        }
        return delivered;
    }

    private boolean deliver(NotificationOutboxPort.PendingNotification pending) {
        try {
            mailSender.send(pending.message());
            outbox.markSent(pending.id(), clock.instant());
            return true;
        } catch (RuntimeException e) {
            Instant now = clock.instant();
            String error = e.toString();
            if (retryPolicy.canRetry(pending.attempts())) {
                Instant next = retryPolicy.nextAttemptAt(pending.attempts(), now);
                log.warn("Notification {} failed (attempt {}), retrying at {}: {}",
                        pending.id(), pending.attempts(), next, error);
                outbox.reschedule(pending.id(), error, next);
            } else {
                log.error("Notification {} gave up after {} attempts: {}",
                        pending.id(), pending.attempts(), error);
                outbox.markFailed(pending.id(), error, now);
            }
            return false;
        }
    }
}
