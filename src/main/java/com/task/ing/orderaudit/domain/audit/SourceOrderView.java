package com.task.ing.orderaudit.domain.audit;

import com.task.ing.orderaudit.domain.model.EventRef;
import com.task.ing.orderaudit.domain.model.OrderSnapshot;
import com.task.ing.orderaudit.domain.model.PaymentSnapshot;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record SourceOrderView(
        String orderId,
        Optional<OrderSnapshot> order,
        Optional<PaymentSnapshot> payment,
        long orderEventCount,
        long paymentEventCount,
        List<EventRef> orderEvents,
        List<EventRef> paymentEvents,
        boolean eventDetailsAvailable) {
    public SourceOrderView {
        Objects.requireNonNull(orderId, "orderId must not be null");
        order = order == null ? Optional.empty() : order;
        payment = payment == null ? Optional.empty() : payment;
        orderEvents = orderEvents == null ? List.of() : List.copyOf(orderEvents);
        paymentEvents = paymentEvents == null ? List.of() : List.copyOf(paymentEvents);
    }

    public static SourceOrderView withCountsOnly(
            String orderId,
            Optional<OrderSnapshot> order,
            Optional<PaymentSnapshot> payment,
            long orderEventCount,
            long paymentEventCount) {
        return new SourceOrderView(
                orderId, order, payment, orderEventCount, paymentEventCount, List.of(), List.of(), false);
    }

    public static SourceOrderView withEventDetails(
            String orderId,
            Optional<OrderSnapshot> order,
            Optional<PaymentSnapshot> payment,
            List<EventRef> orderEvents,
            List<EventRef> paymentEvents) {
        return new SourceOrderView(
                orderId, order, payment, orderEvents.size(), paymentEvents.size(),
                orderEvents, paymentEvents, true);
    }
}
