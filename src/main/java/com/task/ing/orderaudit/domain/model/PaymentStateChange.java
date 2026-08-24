package com.task.ing.orderaudit.domain.model;

import java.time.Instant;
import java.util.Objects;

public record PaymentStateChange(
        String paymentId,
        String orderId,
        PaymentStatus status,
        Money amount,
        Instant occurredAt) {
    public PaymentStateChange {
        Objects.requireNonNull(paymentId, "paymentId must not be null");
        Objects.requireNonNull(orderId, "orderId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }

    public PaymentSnapshot applyTo(PaymentSnapshot current) {
        if (current == null) {
            return new PaymentSnapshot(paymentId, orderId, status, amount, occurredAt);
        }
        return new PaymentSnapshot(
                current.paymentId(),
                current.orderId(),
                status != null ? status : current.status(),
                amount != null ? amount : current.amount(),
                occurredAt);
    }
}
