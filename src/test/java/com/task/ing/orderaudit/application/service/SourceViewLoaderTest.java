package com.task.ing.orderaudit.application.service;

import com.task.ing.orderaudit.application.port.out.OrderSourceClient;
import com.task.ing.orderaudit.application.port.out.PaymentSourceClient;
import com.task.ing.orderaudit.application.port.out.SourceEvent;
import com.task.ing.orderaudit.domain.audit.SourceOrderView;
import com.task.ing.orderaudit.domain.model.EventRef;
import com.task.ing.orderaudit.domain.model.OrderStatus;
import com.task.ing.orderaudit.domain.model.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static com.task.ing.orderaudit.support.DomainFixtures.ORDER_ID;
import static com.task.ing.orderaudit.support.DomainFixtures.event;
import static com.task.ing.orderaudit.support.DomainFixtures.local;
import static com.task.ing.orderaudit.support.DomainFixtures.order;
import static com.task.ing.orderaudit.support.DomainFixtures.payment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SourceViewLoaderTest {

    @Mock
    private OrderSourceClient orderClient;
    @Mock
    private PaymentSourceClient paymentClient;

    private SourceViewLoader loader() {
        return new SourceViewLoader(orderClient, paymentClient);
    }

    @Test
    void skips_the_expensive_history_download_when_the_counts_already_agree() {
        given(orderClient.fetchOrder(ORDER_ID)).willReturn(Optional.of(order(OrderStatus.COMPLETED, "150.00")));
        given(paymentClient.fetchPayment(ORDER_ID)).willReturn(Optional.of(payment(PaymentStatus.PAID, "150.00")));
        given(orderClient.countEvents(ORDER_ID)).willReturn(2L);
        given(paymentClient.countEvents(ORDER_ID)).willReturn(1L);

        SourceOrderView view = loader().load(ORDER_ID,
                local(null, null,
                        List.of(event("EVT-1", "ORDER_CREATED"), event("EVT-2", "ORDER_COMPLETED")),
                        List.of(event("PAY-EVT-1", "PAYMENT_COMPLETED"))));

        assertThat(view.eventDetailsAvailable()).isFalse();
        assertThat(view.orderEventCount()).isEqualTo(2);
        verify(orderClient, never()).fetchEvents(ORDER_ID);
        verify(paymentClient, never()).fetchEvents(ORDER_ID);
    }

    @Test
    void downloads_both_histories_as_soon_as_one_count_disagrees() {
        given(orderClient.fetchOrder(ORDER_ID)).willReturn(Optional.empty());
        given(paymentClient.fetchPayment(ORDER_ID)).willReturn(Optional.empty());
        given(orderClient.countEvents(ORDER_ID)).willReturn(2L);
        given(paymentClient.countEvents(ORDER_ID)).willReturn(0L);
        given(orderClient.fetchEvents(ORDER_ID)).willReturn(List.of(
                sourceEvent("EVT-1", "ORDER_CREATED"), sourceEvent("EVT-2", "ORDER_COMPLETED")));
        given(paymentClient.fetchEvents(ORDER_ID)).willReturn(List.of());

        SourceOrderView view = loader().load(ORDER_ID, local(null, null, List.of(), List.of()));

        assertThat(view.eventDetailsAvailable()).isTrue();
        assertThat(view.orderEvents()).extracting(EventRef::eventId).containsExactly("EVT-1", "EVT-2");
        verify(paymentClient).fetchEvents(ORDER_ID);
    }

    @Test
    void a_payment_count_mismatch_alone_also_triggers_the_download() {
        given(orderClient.fetchOrder(ORDER_ID)).willReturn(Optional.empty());
        given(paymentClient.fetchPayment(ORDER_ID)).willReturn(Optional.empty());
        given(orderClient.countEvents(ORDER_ID)).willReturn(0L);
        given(paymentClient.countEvents(ORDER_ID)).willReturn(3L);
        given(orderClient.fetchEvents(ORDER_ID)).willReturn(List.of());
        given(paymentClient.fetchEvents(ORDER_ID)).willReturn(List.of(sourceEvent("PAY-EVT-1", "PAYMENT_PAID")));

        SourceOrderView view = loader().load(ORDER_ID, local(null, null, List.of(), List.of()));

        assertThat(view.eventDetailsAvailable()).isTrue();

        assertThat(view.paymentEventCount()).isEqualTo(3);
        assertThat(view.paymentEvents()).hasSize(1);
    }

    @Test
    void the_resync_path_always_downloads_the_histories() {
        given(orderClient.fetchOrder(ORDER_ID)).willReturn(Optional.of(order(OrderStatus.COMPLETED, "150.00")));
        given(paymentClient.fetchPayment(ORDER_ID)).willReturn(Optional.empty());
        given(orderClient.countEvents(ORDER_ID)).willReturn(1L);
        given(paymentClient.countEvents(ORDER_ID)).willReturn(0L);
        given(orderClient.fetchEvents(ORDER_ID)).willReturn(List.of(sourceEvent("EVT-1", "ORDER_CREATED")));
        given(paymentClient.fetchEvents(ORDER_ID)).willReturn(List.of());

        SourceOrderView view = loader().loadWithEventDetails(ORDER_ID);

        assertThat(view.eventDetailsAvailable()).isTrue();
        assertThat(view.orderEvents()).hasSize(1);
        verify(orderClient).fetchEvents(ORDER_ID);
    }

    private SourceEvent sourceEvent(String eventId, String type) {
        return new SourceEvent(event(eventId, type), ORDER_ID, ORDER_ID, "{}");
    }
}
