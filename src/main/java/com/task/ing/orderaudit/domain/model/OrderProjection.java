package com.task.ing.orderaudit.domain.model;

import java.util.Objects;

public record OrderProjection(OrderSnapshot snapshot, EventRef lastAppliedEvent) {

    public OrderProjection {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
    }
}
