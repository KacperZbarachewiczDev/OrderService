package com.task.ing.orderaudit.domain.audit;

import java.time.Instant;

public record AuditIssue(
        Long id,
        String orderId,
        IssueStatus status,
        Severity highestSeverity,
        int discrepancyCount,
        Instant firstDetectedAt,
        Instant lastDetectedAt,
        Instant resolvedAt,
        Long lastAuditRunId) {
}
