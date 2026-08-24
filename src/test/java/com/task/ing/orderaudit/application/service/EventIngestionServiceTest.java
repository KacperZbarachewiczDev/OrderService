package com.task.ing.orderaudit.application.service;

import com.task.ing.orderaudit.application.port.in.OrderEventCommand;
import com.task.ing.orderaudit.application.port.in.PaymentEventCommand;
import com.task.ing.orderaudit.application.port.out.EventStorePort;
import com.task.ing.orderaudit.application.port.out.OrderProjectionPort;
import com.task.ing.orderaudit.application.port.out.PaymentProjectionPort;
import com.task.ing.orderaudit.domain.ingest.IngestOutcome;
import com.task.ing.orderaudit.domain.model.EventOrigin;
import com.task.ing.orderaudit.domain.model.EventRef;
import com.task.ing.orderaudit.domain.model.EventSource;
import com.task.ing.orderaudit.domain.model.IngestedEvent;
import com.task.ing.orderaudit.domain.model.Money;
import com.task.ing.orderaudit.domain.model.OrderProjection;
import com.task.ing.orderaudit.domain.model.OrderSnapshot;
import com.task.ing.orderaudit.domain.model.OrderStatus;
import com.task.ing.orderaudit.domain.model.PaymentProjection;
import com.task.ing.orderaudit.domain.model.PaymentSnapshot;
import com.task.ing.orderaudit.domain.model.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class EventIngestionServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-19T12:00:00Z");
    private static final Instant EARLY = Instant.parse("2026-08-19T10:00:00Z");
    private static final Instant LATE = Instant.parse("2026-08-19T11:00:00Z");

    @Mock
    private EventStorePort eventStore;
    @Mock
    private OrderProjectionPort orderProjections;
    @Mock
    private PaymentProjectionPort paymentProjections;
    @Captor
    private ArgumentCaptor<OrderProjection> orderCaptor;
    @Captor
    private ArgumentCaptor<IngestedEvent> eventCaptor;

    private EventIngestionService service() {
        return new EventIngestionService(
                eventStore, orderProjections, paymentProjections,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void archives_the_event_and_advances_the_order() {
        given(eventStore.append(any())).willReturn(true);
        given(orderProjections.findForUpdate(eq("ORD-1"), any())).willReturn(Optional.empty());

        IngestOutcome outcome = service().ingestOrderEvent(orderEvent("EVT-1", OrderStatus.CREATED, EARLY));

        assertThat(outcome).isEqualTo(IngestOutcome.APPLIED);
        verify(eventStore).append(eventCaptor.capture());
        assertThat(eventCaptor.getValue().source()).isEqualTo(EventSource.ORDER);
        assertThat(eventCaptor.getValue().receivedAt()).isEqualTo(NOW);
        verify(orderProjections).save(orderCaptor.capture(), any());
        assertThat(orderCaptor.getValue().snapshot().status()).isEqualTo(OrderStatus.CREATED);
        assertThat(orderCaptor.getValue().lastAppliedEvent().eventId()).isEqualTo("EVT-1");
    }

    @Test
    void a_redelivered_message_touches_nothing() {
        given(eventStore.append(any())).willReturn(false);

        IngestOutcome outcome = service().ingestOrderEvent(orderEvent("EVT-1", OrderStatus.CREATED, EARLY));

        assertThat(outcome).isEqualTo(IngestOutcome.DUPLICATE);
        verify(orderProjections, never()).findForUpdate(any(), any());
        verify(orderProjections, never()).save(any(), any());
    }

    @Test
    void an_event_older_than_the_current_state_is_archived_but_not_applied() {
        given(eventStore.append(any())).willReturn(true);
        given(orderProjections.findForUpdate(eq("ORD-1"), any())).willReturn(Optional.of(new OrderProjection(
                new OrderSnapshot("ORD-1", "CUST-1", OrderStatus.COMPLETED, null, List.of(), LATE),
                EventRef.of("EVT-9", "ORDER_COMPLETED", LATE))));

        IngestOutcome outcome = service().ingestOrderEvent(orderEvent("EVT-1", OrderStatus.CREATED, EARLY));

        assertThat(outcome).isEqualTo(IngestOutcome.STORED_OUT_OF_ORDER);
        verify(eventStore).append(any());
        verify(orderProjections, never()).save(any(), any());
    }

    @Test
    void a_later_event_folds_onto_the_state_instead_of_replacing_it() {
        given(eventStore.append(any())).willReturn(true);
        given(orderProjections.findForUpdate(eq("ORD-1"), any())).willReturn(Optional.of(new OrderProjection(
                new OrderSnapshot("ORD-1", "CUST-1", OrderStatus.CREATED,
                        Money.of("150.00", "PLN"), List.of(), EARLY),
                EventRef.of("EVT-1", "ORDER_CREATED", EARLY))));

        IngestOutcome outcome = service().ingestOrderEvent(
                new OrderEventCommand("EVT-2", "ORD-1", "ORDER_COMPLETED", null,
                        OrderStatus.COMPLETED, null, null, LATE, null, "{}", EventOrigin.KAFKA));

        assertThat(outcome).isEqualTo(IngestOutcome.APPLIED);
        verify(orderProjections).save(orderCaptor.capture(), any());
        assertThat(orderCaptor.getValue().snapshot().status()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(orderCaptor.getValue().snapshot().customerId()).isEqualTo("CUST-1");
        assertThat(orderCaptor.getValue().snapshot().totalAmount()).isEqualTo(Money.of("150.00", "PLN"));
    }

    @Test
    void archives_the_payment_event_and_advances_the_payment() {
        given(eventStore.append(any())).willReturn(true);
        given(paymentProjections.findForUpdate(eq("PAY-1"), eq("ORD-1"), any())).willReturn(Optional.empty());

        IngestOutcome outcome = service().ingestPaymentEvent(paymentEvent("PAY-EVT-1", PaymentStatus.PAID, EARLY));

        assertThat(outcome).isEqualTo(IngestOutcome.APPLIED);
        verify(eventStore).append(eventCaptor.capture());
        assertThat(eventCaptor.getValue().source()).isEqualTo(EventSource.PAYMENT);
        assertThat(eventCaptor.getValue().aggregateId()).isEqualTo("PAY-1");
        verify(paymentProjections).save(any(), any());
    }

    @Test
    void a_redelivered_payment_message_touches_nothing() {
        given(eventStore.append(any())).willReturn(false);

        assertThat(service().ingestPaymentEvent(paymentEvent("PAY-EVT-1", PaymentStatus.PAID, EARLY)))
                .isEqualTo(IngestOutcome.DUPLICATE);
        verify(paymentProjections, never()).save(any(), any());
    }

    @Test
    void an_older_payment_event_is_archived_but_not_applied() {
        given(eventStore.append(any())).willReturn(true);
        given(paymentProjections.findForUpdate(eq("PAY-1"), eq("ORD-1"), any())).willReturn(Optional.of(new PaymentProjection(
                new PaymentSnapshot("PAY-1", "ORD-1", PaymentStatus.PAID, null, LATE),
                EventRef.of("PAY-EVT-9", "PAYMENT_COMPLETED", LATE))));

        assertThat(service().ingestPaymentEvent(paymentEvent("PAY-EVT-1", PaymentStatus.PENDING, EARLY)))
                .isEqualTo(IngestOutcome.STORED_OUT_OF_ORDER);
        verify(paymentProjections, never()).save(any(), any());
    }

    private OrderEventCommand orderEvent(String eventId, OrderStatus status, Instant occurredAt) {
        return new OrderEventCommand(eventId, "ORD-1", "ORDER_CREATED", "CUST-1", status,
                Money.of("150.00", "PLN"), List.of(), occurredAt, null, "{}", EventOrigin.KAFKA);
    }

    private PaymentEventCommand paymentEvent(String eventId, PaymentStatus status, Instant occurredAt) {
        return new PaymentEventCommand(eventId, "PAY-1", "ORD-1", "PAYMENT_COMPLETED", status,
                Money.of("150.00", "PLN"), occurredAt, null, "{}", EventOrigin.KAFKA);
    }
}
