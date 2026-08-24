package com.task.ing.orderaudit.domain.notification;

import com.task.ing.orderaudit.domain.audit.AuditRun;
import com.task.ing.orderaudit.domain.audit.AuditRunStats;
import com.task.ing.orderaudit.domain.audit.Severity;

import java.util.List;
import java.util.Objects;

public class AuditNotificationComposer {

    private final int maxListedIssues;
    private final String issuesUrl;

    public AuditNotificationComposer(int maxListedIssues, String issuesUrl) {
        if (maxListedIssues < 1) {
            throw new IllegalArgumentException("maxListedIssues must be at least 1");
        }
        this.maxListedIssues = maxListedIssues;
        this.issuesUrl = issuesUrl;
    }

    public NotificationMessage compose(List<String> recipients, AuditRun run, List<IssueDigest> issues) {
        Objects.requireNonNull(run, "run must not be null");
        List<IssueDigest> findings = issues == null ? List.of() : issues;
        return new NotificationMessage(recipients, subject(run, findings), body(run, findings));
    }

    private String subject(AuditRun run, List<IssueDigest> issues) {
        long critical = issues.stream().filter(issue -> issue.severity() == Severity.CRITICAL).count();
        StringBuilder subject = new StringBuilder("[Order Audit] run #")
                .append(run.id())
                .append(": ")
                .append(run.stats().ordersWithIssues())
                .append(run.stats().ordersWithIssues() == 1 ? " order" : " orders")
                .append(" with issues");
        if (critical > 0) {
            subject.append(" (").append(critical).append(" critical)");
        }
        return subject.toString();
    }

    private String body(AuditRun run, List<IssueDigest> issues) {
        AuditRunStats stats = run.stats();
        StringBuilder body = new StringBuilder();
        body.append("Audit run #").append(run.id()).append(" finished.\n\n");
        body.append("Window:       ").append(run.windowFrom()).append(" -> ").append(run.windowTo()).append('\n');
        body.append("Started:      ").append(run.startedAt()).append('\n');
        body.append("Finished:     ").append(run.finishedAt()).append('\n');
        body.append("Checked:      ").append(stats.ordersChecked()).append(" orders\n");
        body.append("With issues:  ").append(stats.ordersWithIssues()).append('\n');
        body.append("Resolved:     ").append(stats.ordersResolved()).append('\n');
        body.append("Discrepancies:").append(' ').append(stats.discrepanciesFound()).append('\n');
        if (stats.ordersInconclusive() > 0) {
            body.append("Inconclusive: ").append(stats.ordersInconclusive())
                    .append(" orders could not be verified because a source system was unavailable\n");
        }
        body.append('\n');

        if (issues.isEmpty()) {
            body.append("No individual issues were listed for this run.\n");
        } else {
            body.append("Affected orders:\n");
            issues.stream().limit(maxListedIssues).forEach(issue -> body
                    .append("  - ").append(issue.orderId())
                    .append(" [").append(issue.severity()).append("] ")
                    .append(issue.discrepancyCount()).append(" discrepancy(ies): ")
                    .append(String.join(", ", issue.types()))
                    .append('\n'));
            int listed = Math.min(issues.size(), maxListedIssues);
            int undisclosed = stats.ordersWithIssues() - listed;
            if (undisclosed > 0) {
                body.append("  ... and ").append(undisclosed).append(" more\n");
            }
        }
        if (issuesUrl != null && !issuesUrl.isBlank()) {
            body.append("\nFull list: ").append(issuesUrl).append('\n');
        }
        return body.toString();
    }
}
