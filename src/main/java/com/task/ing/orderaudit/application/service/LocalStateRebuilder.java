package com.task.ing.orderaudit.application.service;

import com.task.ing.orderaudit.application.port.out.EventStorePort;
import com.task.ing.orderaudit.application.port.out.OrderProjectionPort;
import com.task.ing.orderaudit.application.port.out.PaymentProjectionPort;
import com.task.ing.orderaudit.application.port.out.SourceEvent;
import com.task.ing.orderaudit.domain.model.EventOrigin;
import com.task.ing.orderaudit.domain.model.EventRef;
import com.task.ing.orderaudit.domain.model.EventSource;
import com.task.ing.orderaudit.domain.model.IngestedEvent;
import com.task.ing.orderaudit.domain.model.OrderProjection;
import com.task.ing.orderaudit.domain.model.OrderSnapshot;
import com.task.ing.orderaudit.domain.model.PaymentProjection;
import com.task.ing.orderaudit.domain.model.PaymentSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class LocalStateRebuilder {

    private final EventStorePort eventStore;
    private final OrderProjectionPort orderProjections;
    private final PaymentProjectionPort paymentProjections;

    @Transactional
    public void rebuild(
            String orderId,
            Optional<OrderSnapshot> order,
            List<SourceEvent> orderEvents,
            Optional<PaymentSnapshot> payment,
            List<SourceEvent> paymentEvents,
            Instant now) {
        orderEvents.forEach(event -> eventStore.append(toIngestedEvent(event, EventSource.ORDER, orderId, now)));
        paymentEvents.forEach(event -> eventStore.append(toIngestedEvent(event, EventSource.PAYMENT, orderId, now)));

        order.ifPresent(snapshot ->
                orderProjections.overwrite(new OrderProjection(snapshot, latestRef(orderEvents)), now));
        payment.ifPresent(snapshot ->
                paymentProjections.overwrite(new PaymentProjection(snapshot, latestRef(paymentEvents)), now));
    }

    private IngestedEvent toIngestedEvent(SourceEvent event, EventSource source, String orderId, Instant now) {
        EventRef ref = event.ref();
        return new IngestedEvent(
                ref.eventId(),
                source,
                orderId,
                event.aggregateId() != null ? event.aggregateId() : orderId,
                ref.eventType(),
                ref.occurredAt(),
                ref.sequenceNo(),
                event.rawPayload(),
                now,
                EventOrigin.RESYNC);
    }

    private EventRef latestRef(List<SourceEvent> events) {
        return events.stream()
                .map(SourceEvent::ref)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }
}
