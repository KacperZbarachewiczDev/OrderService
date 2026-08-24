package com.task.ing.orderaudit.domain.resync;

import java.time.Instant;

public record ResyncJob(
        Long id,
        String orderId,
        ResyncStatus status,
        Instant requestedAt,
        Instant startedAt,
        Instant finishedAt,
        int attempts,
        Integer remainingDiscrepancies,
        String failureReason) {
}
