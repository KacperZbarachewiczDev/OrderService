package com.task.ing.orderaudit.domain.model;

import java.time.Instant;
import java.util.Objects;

public record PaymentSnapshot(
        String paymentId,
        String orderId,
        PaymentStatus status,
        Money amount,
        Instant updatedAt) {
    public PaymentSnapshot {
        Objects.requireNonNull(paymentId, "paymentId must not be null");
        Objects.requireNonNull(orderId, "orderId must not be null");
        status = status == null ? PaymentStatus.UNKNOWN : status;
    }
}
