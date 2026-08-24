package com.task.ing.orderaudit.support;

import com.task.ing.orderaudit.application.config.AuditProperties;

import java.time.Duration;
import java.util.List;

public final class TestProperties {

    private TestProperties() {
    }

    public static AuditProperties audit() {
        return audit(25, 100);
    }

    public static AuditProperties audit(int maxListedIssues, int maxCollectedDigests) {
        return new AuditProperties(
                Duration.ofDays(30),
                500,
                "daily-audit",
                Duration.ofHours(2),
                new AuditProperties.Notification(
                        List.of("ops@example.com"), "audit@example.com",
                        maxListedIssues, maxCollectedDigests, "http://localhost/issues"),
                new AuditProperties.Resync(4, 100, Duration.ofMinutes(2), 50),
                new AuditProperties.Outbox(
                        20, 3, Duration.ofSeconds(30), Duration.ofMinutes(10), Duration.ofMinutes(5)));
    }

    public static AuditProperties withoutRecipients() {
        AuditProperties base = audit();
        return new AuditProperties(
                base.initialLookback(), base.candidateBatchSize(), base.lockName(), base.lockLease(),
                new AuditProperties.Notification(
                        List.of(), "audit@example.com", 25, 100, "http://localhost/issues"),
                base.resync(), base.outbox());
    }
}
