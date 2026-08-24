package com.task.ing.orderaudit.adapter.out.persistence;

import com.task.ing.orderaudit.adapter.out.persistence.entity.PaymentEntity;
import com.task.ing.orderaudit.adapter.out.persistence.repository.PaymentJpaRepository;
import com.task.ing.orderaudit.application.port.out.PaymentProjectionPort;
import com.task.ing.orderaudit.domain.model.EventRef;
import com.task.ing.orderaudit.domain.model.Money;
import com.task.ing.orderaudit.domain.model.PaymentProjection;
import com.task.ing.orderaudit.domain.model.PaymentSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class PaymentProjectionJpaAdapter implements PaymentProjectionPort {

    private final PaymentJpaRepository repository;

    @Override
    @Transactional(readOnly = true)
    public Optional<PaymentProjection> findByOrderId(String orderId) {
        return repository.findByOrderId(orderId).map(PersistenceMapper::toProjection);
    }

    @Override
    @Transactional
    public Optional<PaymentProjection> findForUpdate(String paymentId, String orderId, Instant now) {
        repository.reserve(paymentId, orderId, now);
        return repository.findForUpdate(paymentId).map(PersistenceMapper::toProjection);
    }

    @Override
    @Transactional
    public void save(PaymentProjection projection, Instant now) {
        write(projection, now);
    }

    @Override
    @Transactional
    public void overwrite(PaymentProjection projection, Instant now) {
        write(projection, now);
    }

    private void write(PaymentProjection projection, Instant now) {
        PaymentSnapshot snapshot = projection.snapshot();
        repository.reserve(snapshot.paymentId(), snapshot.orderId(), now);
        PaymentEntity entity = repository.findForUpdate(snapshot.paymentId())
                .orElseThrow(() -> new IllegalStateException(
                        "payment " + snapshot.paymentId() + " vanished right after being reserved"));

        entity.setStatus(snapshot.status());
        Money amount = snapshot.amount();
        entity.setAmount(amount == null ? null : amount.amount());
        entity.setCurrency(amount == null ? null : amount.currency());
        entity.setUpdatedAt(now);

        EventRef applied = projection.lastAppliedEvent();
        entity.setLastEventId(applied == null ? null : applied.eventId());
        entity.setLastEventType(applied == null ? null : applied.eventType());
        entity.setLastEventOccurredAt(
                applied != null ? applied.occurredAt() : snapshot.updatedAt());
        entity.setLastEventSequence(applied == null ? null : applied.sequenceNo());

        repository.save(entity);
    }
}
