package com.task.ing.orderaudit.domain.model;

import java.time.Instant;
import java.util.Objects;

public record IngestedEvent(
        String eventId,
        EventSource source,
        String orderId,
        String aggregateId,
        String eventType,
        Instant occurredAt,
        Long sequenceNo,
        String payload,
        Instant receivedAt,
        EventOrigin origin) {
    public IngestedEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(orderId, "orderId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(receivedAt, "receivedAt must not be null");
        Objects.requireNonNull(origin, "origin must not be null");
        payload = payload == null ? "{}" : payload;
    }

    public EventRef ref() {
        return new EventRef(eventId, eventType, occurredAt, sequenceNo);
    }
}
