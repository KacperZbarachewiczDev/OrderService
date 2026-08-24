package com.task.ing.orderaudit.application.service;

import com.task.ing.orderaudit.application.port.out.OrderSourceClient;
import com.task.ing.orderaudit.application.port.out.PaymentSourceClient;
import com.task.ing.orderaudit.application.port.out.SourceEvent;
import com.task.ing.orderaudit.domain.audit.LocalOrderView;
import com.task.ing.orderaudit.domain.audit.SourceOrderView;
import com.task.ing.orderaudit.domain.model.EventRef;
import com.task.ing.orderaudit.domain.model.OrderSnapshot;
import com.task.ing.orderaudit.domain.model.PaymentSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class SourceViewLoader {

    private final OrderSourceClient orderClient;
    private final PaymentSourceClient paymentClient;

    public SourceOrderView load(String orderId, LocalOrderView local) {
        Optional<OrderSnapshot> order = orderClient.fetchOrder(orderId);
        Optional<PaymentSnapshot> payment = paymentClient.fetchPayment(orderId);
        long orderEventCount = orderClient.countEvents(orderId);
        long paymentEventCount = paymentClient.countEvents(orderId);

        boolean countsAgree = orderEventCount == local.orderEvents().size()
                && paymentEventCount == local.paymentEvents().size();
        if (countsAgree) {
            return SourceOrderView.withCountsOnly(orderId, order, payment, orderEventCount, paymentEventCount);
        }
        return new SourceOrderView(
                orderId,
                order,
                payment,
                orderEventCount,
                paymentEventCount,
                refs(orderClient.fetchEvents(orderId)),
                refs(paymentClient.fetchEvents(orderId)),
                true);
    }

    public SourceOrderView loadWithEventDetails(String orderId) {
        return new SourceOrderView(
                orderId,
                orderClient.fetchOrder(orderId),
                paymentClient.fetchPayment(orderId),
                orderClient.countEvents(orderId),
                paymentClient.countEvents(orderId),
                refs(orderClient.fetchEvents(orderId)),
                refs(paymentClient.fetchEvents(orderId)),
                true);
    }

    private List<EventRef> refs(List<SourceEvent> events) {
        return events.stream().map(SourceEvent::ref).toList();
    }
}
