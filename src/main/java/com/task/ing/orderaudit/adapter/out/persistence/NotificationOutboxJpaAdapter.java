package com.task.ing.orderaudit.adapter.out.persistence;

import com.task.ing.orderaudit.adapter.out.persistence.entity.NotificationOutboxEntity;
import com.task.ing.orderaudit.adapter.out.persistence.repository.NotificationOutboxJpaRepository;
import com.task.ing.orderaudit.application.port.out.NotificationOutboxPort;
import com.task.ing.orderaudit.application.config.AuditProperties;
import com.task.ing.orderaudit.domain.notification.NotificationMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class NotificationOutboxJpaAdapter implements NotificationOutboxPort {

    private static final String RECIPIENT_SEPARATOR = ",";

    private final NotificationOutboxJpaRepository repository;
    private final AuditProperties properties;

    @Override
    @Transactional
    public long enqueue(NotificationMessage message, Long auditRunId, Instant now) {
        NotificationOutboxEntity entity = repository.save(new NotificationOutboxEntity(
                String.join(RECIPIENT_SEPARATOR, message.recipients()),
                message.subject(),
                message.body(),
                now,
                auditRunId));
        return entity.getId();
    }

    @Override
    @Transactional
    public List<PendingNotification> claimDue(Instant now, int limit) {
        List<Long> ids = repository.lockDueIds(now, limit);
        if (ids.isEmpty()) {
            return List.of();
        }
        repository.markClaimed(ids, now.plus(properties.outbox().claimLease()));
        return repository.findAllById(ids).stream()
                .map(entity -> new PendingNotification(
                        entity.getId(),
                        new NotificationMessage(
                                Arrays.stream(entity.getRecipients().split(RECIPIENT_SEPARATOR))
                                        .map(String::trim)
                                        .filter(recipient -> !recipient.isEmpty())
                                        .toList(),
                                entity.getSubject(),
                                entity.getBody()),
                        entity.getAttempts()))
                .toList();
    }

    @Override
    @Transactional
    public void markSent(long id, Instant sentAt) {
        repository.markSent(id, sentAt);
    }

    @Override
    @Transactional
    public void reschedule(long id, String error, Instant nextAttemptAt) {
        repository.reschedule(id, error, nextAttemptAt);
    }

    @Override
    @Transactional
    public void markFailed(long id, String error, Instant failedAt) {
        repository.markFailed(id, error, failedAt);
    }
}
