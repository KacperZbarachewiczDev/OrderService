package com.task.ing.orderaudit.application.port.in;

import com.task.ing.orderaudit.domain.model.EventOrigin;
import com.task.ing.orderaudit.domain.model.Money;
import com.task.ing.orderaudit.domain.model.PaymentStatus;

import java.time.Instant;
import java.util.Objects;

public record PaymentEventCommand(
        String eventId,
        String paymentId,
        String orderId,
        String eventType,
        PaymentStatus status,
        Money amount,
        Instant occurredAt,
        Long sequenceNo,
        String rawPayload,
        EventOrigin origin) {
    public PaymentEventCommand {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(paymentId, "paymentId must not be null");
        Objects.requireNonNull(orderId, "orderId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(origin, "origin must not be null");
    }
}
