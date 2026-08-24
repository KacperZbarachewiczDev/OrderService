package com.task.ing.orderaudit.application.port.out;

import com.task.ing.orderaudit.domain.model.EventRef;

import java.util.Objects;

public record SourceEvent(EventRef ref, String orderId, String aggregateId, String rawPayload) {

    public SourceEvent {
        Objects.requireNonNull(ref, "ref must not be null");
        Objects.requireNonNull(orderId, "orderId must not be null");
    }
}
