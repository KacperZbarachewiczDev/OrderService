package com.task.ing.orderaudit.adapter.out.persistence;

import com.task.ing.orderaudit.adapter.out.persistence.repository.IngestedEventJpaRepository;
import com.task.ing.orderaudit.application.port.out.EventStorePort;
import com.task.ing.orderaudit.domain.model.EventRef;
import com.task.ing.orderaudit.domain.model.EventSource;
import com.task.ing.orderaudit.domain.model.IngestedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class EventStoreJpaAdapter implements EventStorePort {

    private final IngestedEventJpaRepository repository;

    @Override
    @Transactional
    public boolean append(IngestedEvent event) {
        return repository.insertIfAbsent(
                event.eventId(),
                event.source().name(),
                event.orderId(),
                event.aggregateId(),
                event.eventType(),
                event.occurredAt(),
                event.sequenceNo(),
                event.payload(),
                event.receivedAt(),
                event.origin().name()) == 1;
    }

    @Override
    @Transactional(readOnly = true)
    public List<EventRef> findEventRefs(String orderId, EventSource source) {
        return repository.findRefs(orderId, source);
    }
}
