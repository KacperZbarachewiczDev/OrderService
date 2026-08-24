package com.task.ing.orderaudit.application.service;

import com.task.ing.orderaudit.application.port.in.IngestEventUseCase;
import com.task.ing.orderaudit.application.port.in.OrderEventCommand;
import com.task.ing.orderaudit.application.port.in.PaymentEventCommand;
import com.task.ing.orderaudit.application.port.out.EventStorePort;
import com.task.ing.orderaudit.application.port.out.OrderProjectionPort;
import com.task.ing.orderaudit.application.port.out.PaymentProjectionPort;
import com.task.ing.orderaudit.domain.ingest.EventOrdering;
import com.task.ing.orderaudit.domain.ingest.IngestOutcome;
import com.task.ing.orderaudit.domain.model.EventRef;
import com.task.ing.orderaudit.domain.model.EventSource;
import com.task.ing.orderaudit.domain.model.IngestedEvent;
import com.task.ing.orderaudit.domain.model.OrderProjection;
import com.task.ing.orderaudit.domain.model.OrderSnapshot;
import com.task.ing.orderaudit.domain.model.OrderStateChange;
import com.task.ing.orderaudit.domain.model.PaymentProjection;
import com.task.ing.orderaudit.domain.model.PaymentSnapshot;
import com.task.ing.orderaudit.domain.model.PaymentStateChange;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class EventIngestionService implements IngestEventUseCase {

    private final EventStorePort eventStore;
    private final OrderProjectionPort orderProjections;
    private final PaymentProjectionPort paymentProjections;
    private final Clock clock;

    @Override
    @Transactional
    public IngestOutcome ingestOrderEvent(OrderEventCommand command) {
        Instant now = clock.instant();
        IngestedEvent event = new IngestedEvent(
                command.eventId(),
                EventSource.ORDER,
                command.orderId(),
                command.orderId(),
                command.eventType(),
                command.occurredAt(),
                command.sequenceNo(),
                command.rawPayload(),
                now,
                command.origin());

        if (!eventStore.append(event)) {
            log.debug("Duplicate order event {} ignored", command.eventId());
            return IngestOutcome.DUPLICATE;
        }

        EventRef incoming = event.ref();
        Optional<OrderProjection> current = orderProjections.findForUpdate(command.orderId(), now);
        EventRef lastApplied = current.map(OrderProjection::lastAppliedEvent).orElse(null);

        if (!EventOrdering.shouldApply(incoming, lastApplied)) {
            log.debug("Order event {} is older than the state of {}; archived without applying",
                    command.eventId(), command.orderId());
            return IngestOutcome.STORED_OUT_OF_ORDER;
        }

        OrderStateChange change = new OrderStateChange(
                command.orderId(),
                command.customerId(),
                command.status(),
                command.totalAmount(),
                command.lines(),
                command.occurredAt());
        OrderSnapshot updated = change.applyTo(current.map(OrderProjection::snapshot).orElse(null));
        orderProjections.save(new OrderProjection(updated, incoming), now);
        return IngestOutcome.APPLIED;
    }

    @Override
    @Transactional
    public IngestOutcome ingestPaymentEvent(PaymentEventCommand command) {
        Instant now = clock.instant();
        IngestedEvent event = new IngestedEvent(
                command.eventId(),
                EventSource.PAYMENT,
                command.orderId(),
                command.paymentId(),
                command.eventType(),
                command.occurredAt(),
                command.sequenceNo(),
                command.rawPayload(),
                now,
                command.origin());

        if (!eventStore.append(event)) {
            log.debug("Duplicate payment event {} ignored", command.eventId());
            return IngestOutcome.DUPLICATE;
        }

        EventRef incoming = event.ref();
        Optional<PaymentProjection> current =
                paymentProjections.findForUpdate(command.paymentId(), command.orderId(), now);
        EventRef lastApplied = current.map(PaymentProjection::lastAppliedEvent).orElse(null);

        if (!EventOrdering.shouldApply(incoming, lastApplied)) {
            log.debug("Payment event {} is older than the state of {}; archived without applying",
                    command.eventId(), command.orderId());
            return IngestOutcome.STORED_OUT_OF_ORDER;
        }

        PaymentStateChange change = new PaymentStateChange(
                command.paymentId(),
                command.orderId(),
                command.status(),
                command.amount(),
                command.occurredAt());
        PaymentSnapshot updated = change.applyTo(current.map(PaymentProjection::snapshot).orElse(null));
        paymentProjections.save(new PaymentProjection(updated, incoming), now);
        return IngestOutcome.APPLIED;
    }
}
