package com.task.ing.orderaudit.application.service;

import com.task.ing.orderaudit.application.port.out.EventStorePort;
import com.task.ing.orderaudit.application.port.out.OrderProjectionPort;
import com.task.ing.orderaudit.application.port.out.PaymentProjectionPort;
import com.task.ing.orderaudit.application.port.out.SourceEvent;
import com.task.ing.orderaudit.domain.model.EventOrigin;
import com.task.ing.orderaudit.domain.model.EventRef;
import com.task.ing.orderaudit.domain.model.IngestedEvent;
import com.task.ing.orderaudit.domain.model.OrderProjection;
import com.task.ing.orderaudit.domain.model.OrderStatus;
import com.task.ing.orderaudit.domain.model.PaymentProjection;
import com.task.ing.orderaudit.domain.model.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.task.ing.orderaudit.support.DomainFixtures.ORDER_ID;
import static com.task.ing.orderaudit.support.DomainFixtures.event;
import static com.task.ing.orderaudit.support.DomainFixtures.order;
import static com.task.ing.orderaudit.support.DomainFixtures.payment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class LocalStateRebuilderTest {

    private static final Instant NOW = Instant.parse("2026-08-19T12:00:00Z");

    @Mock
    private EventStorePort eventStore;
    @Mock
    private OrderProjectionPort orderProjections;
    @Mock
    private PaymentProjectionPort paymentProjections;
    @Captor
    private ArgumentCaptor<IngestedEvent> eventCaptor;
    @Captor
    private ArgumentCaptor<OrderProjection> orderCaptor;
    @Captor
    private ArgumentCaptor<PaymentProjection> paymentCaptor;

    private LocalStateRebuilder rebuilder() {
        return new LocalStateRebuilder(eventStore, orderProjections, paymentProjections);
    }

    @Test
    void archives_every_downloaded_event_and_marks_where_it_came_from() {
        rebuilder().rebuild(ORDER_ID,
                Optional.of(order(OrderStatus.COMPLETED, "150.00")),
                List.of(sourceEvent("EVT-1", "ORDER_CREATED"), sourceEvent("EVT-2", "ORDER_COMPLETED")),
                Optional.empty(), List.of(), NOW);

        verify(eventStore, times(2)).append(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues())
                .allSatisfy(stored -> {
                    assertThat(stored.origin()).isEqualTo(EventOrigin.RESYNC);
                    assertThat(stored.receivedAt()).isEqualTo(NOW);
                    assertThat(stored.orderId()).isEqualTo(ORDER_ID);
                });
    }

    @Test
    void points_the_rebuilt_order_at_the_newest_event_it_pulled_in() {
        rebuilder().rebuild(ORDER_ID,
                Optional.of(order(OrderStatus.COMPLETED, "150.00")),
                List.of(sourceEvent("EVT-1", "ORDER_CREATED"), sourceEvent("EVT-2", "ORDER_COMPLETED")),
                Optional.empty(), List.of(), NOW);

        verify(orderProjections).overwrite(orderCaptor.capture(), any());
        assertThat(orderCaptor.getValue().lastAppliedEvent().eventId()).isEqualTo("EVT-2");
        assertThat(orderCaptor.getValue().snapshot().status()).isEqualTo(OrderStatus.COMPLETED);
    }

    @Test
    void rebuilds_the_payment_and_archives_its_history_too() {
        rebuilder().rebuild(ORDER_ID,
                Optional.empty(), List.of(),
                Optional.of(payment(PaymentStatus.PAID, "150.00")),
                List.of(sourceEvent("PAY-EVT-1", "PAYMENT_COMPLETED")), NOW);

        verify(paymentProjections).overwrite(paymentCaptor.capture(), any());
        assertThat(paymentCaptor.getValue().snapshot().status()).isEqualTo(PaymentStatus.PAID);
        assertThat(paymentCaptor.getValue().lastAppliedEvent().eventId()).isEqualTo("PAY-EVT-1");

        verify(eventStore).append(eventCaptor.capture());
        assertThat(eventCaptor.getValue().eventId()).isEqualTo("PAY-EVT-1");
        assertThat(eventCaptor.getValue().source())
                .isEqualTo(com.task.ing.orderaudit.domain.model.EventSource.PAYMENT);
    }

    @Test
    void leaves_the_reference_empty_when_the_source_has_no_history_at_all() {
        rebuilder().rebuild(ORDER_ID,
                Optional.of(order(OrderStatus.COMPLETED, "150.00")), List.of(),
                Optional.empty(), List.of(), NOW);

        verify(orderProjections).overwrite(orderCaptor.capture(), any());
        assertThat(orderCaptor.getValue().lastAppliedEvent()).isNull();
    }

    @Test
    void never_deletes_what_the_source_no_longer_reports() {
        rebuilder().rebuild(ORDER_ID, Optional.empty(), List.of(), Optional.empty(), List.of(), NOW);

        verify(orderProjections, never()).overwrite(any(), any());
        verify(paymentProjections, never()).overwrite(any(), any());
        verify(eventStore, never()).append(any());
    }

    @Test
    void keeps_the_aggregate_an_event_names_and_falls_back_to_the_order_when_it_names_none() {
        rebuilder().rebuild(ORDER_ID, Optional.empty(),
                List.of(new SourceEvent(event("EVT-1", "ORDER_CREATED"), ORDER_ID, null, "{}"),
                        new SourceEvent(event("EVT-2", "ORDER_PAID"), ORDER_ID, "AGG-7", "{\"a\":1}")),
                Optional.empty(), List.of(), NOW);

        verify(eventStore, times(2)).append(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues())
                .extracting(IngestedEvent::aggregateId)
                .containsExactly(ORDER_ID, "AGG-7");

        assertThat(eventCaptor.getAllValues())
                .extracting(IngestedEvent::payload)
                .containsExactly("{}", "{\"a\":1}");
    }

    private SourceEvent sourceEvent(String eventId, String type) {
        EventRef ref = event(eventId, type);
        return new SourceEvent(ref, ORDER_ID, ORDER_ID, "{\"eventId\":\"" + eventId + "\"}");
    }
}
