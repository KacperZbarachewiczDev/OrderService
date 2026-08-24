package com.task.ing.orderaudit.domain.audit;

import java.time.Instant;

public record AuditRun(
        Long id,
        AuditTrigger trigger,
        AuditRunStatus status,
        Instant windowFrom,
        Instant windowTo,
        Instant startedAt,
        Instant finishedAt,
        AuditRunStats stats,
        String failureReason) {
}
