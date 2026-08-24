package com.task.ing.orderaudit.adapter.in.rest.dto;

import com.task.ing.orderaudit.domain.audit.AuditIssue;

import java.time.Instant;

public record AuditIssueResponse(
        String orderId,
        String status,
        String highestSeverity,
        int discrepancyCount,
        Instant firstDetectedAt,
        Instant lastDetectedAt,
        Instant resolvedAt,
        Long lastAuditRunId) {
    public static AuditIssueResponse from(AuditIssue issue) {
        return new AuditIssueResponse(
                issue.orderId(),
                issue.status().name(),
                issue.highestSeverity() == null ? null : issue.highestSeverity().name(),
                issue.discrepancyCount(),
                issue.firstDetectedAt(),
                issue.lastDetectedAt(),
                issue.resolvedAt(),
                issue.lastAuditRunId());
    }
}
