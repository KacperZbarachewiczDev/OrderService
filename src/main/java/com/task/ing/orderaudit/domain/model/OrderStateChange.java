package com.task.ing.orderaudit.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record OrderStateChange(
        String orderId,
        String customerId,
        OrderStatus status,
        Money totalAmount,
        List<OrderLine> lines,
        Instant occurredAt) {
    public OrderStateChange {
        Objects.requireNonNull(orderId, "orderId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        lines = lines == null ? null : List.copyOf(lines);
    }

    public OrderSnapshot applyTo(OrderSnapshot current) {
        if (current == null) {
            return new OrderSnapshot(orderId, customerId, status, totalAmount, lines, occurredAt);
        }
        return new OrderSnapshot(
                current.orderId(),
                customerId != null ? customerId : current.customerId(),
                status != null ? status : current.status(),
                totalAmount != null ? totalAmount : current.totalAmount(),
                lines != null ? lines : current.lines(),
                occurredAt);
    }
}
