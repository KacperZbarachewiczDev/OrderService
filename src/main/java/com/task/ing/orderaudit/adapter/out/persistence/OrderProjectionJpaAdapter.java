package com.task.ing.orderaudit.adapter.out.persistence;

import com.task.ing.orderaudit.adapter.out.persistence.entity.OrderEntity;
import com.task.ing.orderaudit.adapter.out.persistence.entity.OrderLineEntity;
import com.task.ing.orderaudit.adapter.out.persistence.repository.OrderJpaRepository;
import com.task.ing.orderaudit.application.port.out.OrderProjectionPort;
import com.task.ing.orderaudit.domain.model.EventRef;
import com.task.ing.orderaudit.domain.model.Money;
import com.task.ing.orderaudit.domain.model.OrderProjection;
import com.task.ing.orderaudit.domain.model.OrderSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class OrderProjectionJpaAdapter implements OrderProjectionPort {

    private final OrderJpaRepository repository;

    @Override
    @Transactional(readOnly = true)
    public Optional<OrderProjection> find(String orderId) {
        return repository.findById(orderId).map(PersistenceMapper::toProjection);
    }

    @Override
    @Transactional
    public Optional<OrderProjection> findForUpdate(String orderId, Instant now) {
        repository.reserve(orderId, now);
        return repository.findForUpdate(orderId).map(PersistenceMapper::toProjection);
    }

    @Override
    @Transactional
    public void save(OrderProjection projection, Instant now) {
        write(projection, now);
    }

    @Override
    @Transactional
    public void overwrite(OrderProjection projection, Instant now) {
        write(projection, now);
    }

    private void write(OrderProjection projection, Instant now) {
        OrderSnapshot snapshot = projection.snapshot();
        repository.reserve(snapshot.orderId(), now);
        OrderEntity entity = repository.findForUpdate(snapshot.orderId())
                .orElseThrow(() -> new IllegalStateException(
                        "order " + snapshot.orderId() + " vanished right after being reserved"));

        entity.setCustomerId(snapshot.customerId());
        entity.setStatus(snapshot.status());
        Money total = snapshot.totalAmount();
        entity.setTotalAmount(total == null ? null : total.amount());
        entity.setCurrency(total == null ? null : total.currency());
        entity.setSourceUpdatedAt(snapshot.updatedAt());
        entity.setUpdatedAt(now);

        EventRef applied = projection.lastAppliedEvent();
        entity.setLastEventId(applied == null ? null : applied.eventId());
        entity.setLastEventType(applied == null ? null : applied.eventType());
        entity.setLastEventOccurredAt(applied == null ? null : applied.occurredAt());
        entity.setLastEventSequence(applied == null ? null : applied.sequenceNo());

        entity.syncLines(snapshot.lines().stream()
                .map(line -> new OrderLineEntity(
                        line.productId(),
                        line.quantity(),
                        line.unitPrice().amount(),
                        line.unitPrice().currency()))
                .toList());
        repository.save(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> findOrderIdsUpdatedBetween(Instant from, Instant to, int limit, String afterOrderId) {
        return repository.findIdsUpdatedBetween(from, to, afterOrderId, PageRequest.ofSize(limit));
    }
}
