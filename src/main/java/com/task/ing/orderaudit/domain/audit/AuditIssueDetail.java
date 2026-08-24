package com.task.ing.orderaudit.domain.audit;

import java.util.List;

public record AuditIssueDetail(AuditIssue issue, List<Discrepancy> discrepancies) {

    public AuditIssueDetail {
        discrepancies = discrepancies == null ? List.of() : List.copyOf(discrepancies);
    }
}
