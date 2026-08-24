package com.task.ing.orderaudit.application.port.in;

import com.task.ing.orderaudit.domain.model.EventOrigin;
import com.task.ing.orderaudit.domain.model.Money;
import com.task.ing.orderaudit.domain.model.OrderLine;
import com.task.ing.orderaudit.domain.model.OrderStatus;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record OrderEventCommand(
        String eventId,
        String orderId,
        String eventType,
        String customerId,
        OrderStatus status,
        Money totalAmount,
        List<OrderLine> lines,
        Instant occurredAt,
        Long sequenceNo,
        String rawPayload,
        EventOrigin origin) {
    public OrderEventCommand {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(orderId, "orderId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(origin, "origin must not be null");
        lines = lines == null ? null : List.copyOf(lines);
    }
}
