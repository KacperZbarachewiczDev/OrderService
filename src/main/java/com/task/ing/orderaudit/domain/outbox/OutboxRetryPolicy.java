package com.task.ing.orderaudit.domain.outbox;

import java.time.Duration;
import java.time.Instant;

public record OutboxRetryPolicy(int maxAttempts, Duration initialBackoff, Duration maxBackoff) {

    private static final int MAX_SHIFT = 16;

    public OutboxRetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1, was: " + maxAttempts);
        }
        if (initialBackoff.isNegative() || initialBackoff.isZero()) {
            throw new IllegalArgumentException("initialBackoff must be positive");
        }
        if (maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("maxBackoff must not be shorter than initialBackoff");
        }
    }

    public boolean canRetry(int attemptsMade) {
        return attemptsMade < maxAttempts;
    }

    public Instant nextAttemptAt(int attemptsMade, Instant now) {
        int shift = Math.min(Math.max(attemptsMade - 1, 0), MAX_SHIFT);
        long backoffMillis = initialBackoff.toMillis() << shift;
        long capped = Math.min(backoffMillis, maxBackoff.toMillis());
        return now.plusMillis(capped);
    }
}
