package com.task.ing.orderaudit.application.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "audit")
public record AuditProperties(
        @DefaultValue("P30D") Duration initialLookback,
        @DefaultValue("500") int candidateBatchSize,
        @DefaultValue("daily-audit") String lockName,
        @DefaultValue("PT2H") Duration lockLease,
        @DefaultValue Notification notification,
        @DefaultValue Resync resync,
        @DefaultValue Outbox outbox) {
    public record Notification(
            @DefaultValue("audit-alerts@example.com") List<String> recipients,
            @DefaultValue("order-audit@example.com") String from,
            @DefaultValue("25") int maxListedIssues,
            @DefaultValue("100") int maxCollectedDigests,
            @DefaultValue("http://localhost:8080/api/audit/issues") String issuesUrl) {
    }

    public record Resync(
            @DefaultValue("8") int poolSize,
            @DefaultValue("1000") int queueCapacity,
            @DefaultValue("PT2M") Duration staleAfter,
            @DefaultValue("50") int pollLimit) {
    }

    public record Outbox(
            @DefaultValue("20") int batchSize,
            @DefaultValue("5") int maxAttempts,
            @DefaultValue("PT30S") Duration initialBackoff,
            @DefaultValue("PT10M") Duration maxBackoff,
            @DefaultValue("PT5M") Duration claimLease) {
    }
}
