package com.task.ing.orderaudit.support;

import com.task.ing.orderaudit.domain.audit.LocalOrderView;
import com.task.ing.orderaudit.domain.audit.SourceOrderView;
import com.task.ing.orderaudit.domain.model.EventRef;
import com.task.ing.orderaudit.domain.model.Money;
import com.task.ing.orderaudit.domain.model.OrderLine;
import com.task.ing.orderaudit.domain.model.OrderSnapshot;
import com.task.ing.orderaudit.domain.model.OrderStatus;
import com.task.ing.orderaudit.domain.model.PaymentSnapshot;
import com.task.ing.orderaudit.domain.model.PaymentStatus;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public final class DomainFixtures {

    public static final String ORDER_ID = "ORD-1001";
    public static final Instant T0 = Instant.parse("2026-08-19T10:00:00Z");

    private DomainFixtures() {
    }

    public static OrderSnapshot order(OrderStatus status, String total, OrderLine... lines) {
        return new OrderSnapshot(
                ORDER_ID, "CUST-1", status, total == null ? null : Money.of(total, "PLN"),
                Arrays.asList(lines), T0);
    }

    public static OrderLine line(String productId, int quantity, String unitPrice) {
        return new OrderLine(productId, quantity, Money.of(unitPrice, "PLN"));
    }

    public static PaymentSnapshot payment(PaymentStatus status, String amount) {
        return new PaymentSnapshot(
                "PAY-1", ORDER_ID, status, amount == null ? null : Money.of(amount, "PLN"), T0);
    }

    public static EventRef event(String eventId, String eventType) {
        return EventRef.of(eventId, eventType, T0);
    }

    public static LocalOrderView local(
            OrderSnapshot order, PaymentSnapshot payment, List<EventRef> orderEvents,
            List<EventRef> paymentEvents) {
        return new LocalOrderView(
                ORDER_ID, Optional.ofNullable(order), Optional.ofNullable(payment),
                orderEvents, paymentEvents);
    }

    public static SourceOrderView sourceWithCounts(
            OrderSnapshot order, PaymentSnapshot payment, long orderEvents, long paymentEvents) {
        return SourceOrderView.withCountsOnly(
                ORDER_ID, Optional.ofNullable(order), Optional.ofNullable(payment),
                orderEvents, paymentEvents);
    }

    public static SourceOrderView sourceWithEvents(
            OrderSnapshot order, PaymentSnapshot payment, List<EventRef> orderEvents,
            List<EventRef> paymentEvents) {
        return SourceOrderView.withEventDetails(
                ORDER_ID, Optional.ofNullable(order), Optional.ofNullable(payment),
                orderEvents, paymentEvents);
    }

    public static List<EventRef> completedHistory() {
        return List.of(event("EVT-1", "ORDER_CREATED"), event("EVT-2", "ORDER_COMPLETED"));
    }
}
