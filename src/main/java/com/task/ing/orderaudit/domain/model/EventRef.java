package com.task.ing.orderaudit.domain.model;

import java.time.Instant;
import java.util.Objects;

public record EventRef(String eventId, String eventType, Instant occurredAt, Long sequenceNo)
        implements Comparable<EventRef> {
    public EventRef {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }

    public static EventRef of(String eventId, String eventType, Instant occurredAt) {
        return new EventRef(eventId, eventType, occurredAt, null);
    }

    @Override
    public int compareTo(EventRef other) {
        int byTime = occurredAt.compareTo(other.occurredAt);
        if (byTime != 0) {
            return byTime;
        }
        int bySequence = Long.compare(sequenceOrMin(), other.sequenceOrMin());
        if (bySequence != 0) {
            return bySequence;
        }
        return eventId.compareTo(other.eventId);
    }

    private long sequenceOrMin() {
        return sequenceNo == null ? Long.MIN_VALUE : sequenceNo;
    }
}
