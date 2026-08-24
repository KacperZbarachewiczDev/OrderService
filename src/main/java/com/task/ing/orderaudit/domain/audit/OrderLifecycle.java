package com.task.ing.orderaudit.domain.audit;

import com.task.ing.orderaudit.domain.model.OrderStatus;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class OrderLifecycle {

    public static final String CREATION_EVENT = "ORDER_CREATED";

    private static final Map<OrderStatus, String> STATUS_EVENTS = Map.of(
            OrderStatus.CREATED, "ORDER_CREATED",
            OrderStatus.CONFIRMED, "ORDER_CONFIRMED",
            OrderStatus.PAID, "ORDER_PAID",
            OrderStatus.SHIPPED, "ORDER_SHIPPED",
            OrderStatus.COMPLETED, "ORDER_COMPLETED",
            OrderStatus.CANCELLED, "ORDER_CANCELLED");

    private OrderLifecycle() {
    }

    public static Set<String> requiredEventTypes(OrderStatus status) {
        if (status == null || status == OrderStatus.UNKNOWN) {
            return Set.of();
        }
        Set<String> required = new LinkedHashSet<>();
        required.add(CREATION_EVENT);
        String statusEvent = STATUS_EVENTS.get(status);
        if (statusEvent != null) {
            required.add(statusEvent);
        }
        return required;
    }
}
