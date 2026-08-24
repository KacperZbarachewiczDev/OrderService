package com.task.ing.orderaudit.domain.model;

import java.util.Objects;

public record PaymentProjection(PaymentSnapshot snapshot, EventRef lastAppliedEvent) {

    public PaymentProjection {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
    }
}
