package com.task.ing.orderaudit.application.service;

import com.task.ing.orderaudit.domain.audit.Discrepancy;

import java.util.List;

public record OrderAuditOutcome(Kind kind, List<Discrepancy> discrepancies, String reason) {

    public enum Kind {
        CLEAN,

        RESOLVED,

        ISSUE,

        INCONCLUSIVE
    }

    public OrderAuditOutcome {
        discrepancies = discrepancies == null ? List.of() : List.copyOf(discrepancies);
    }

    public static OrderAuditOutcome clean() {
        return new OrderAuditOutcome(Kind.CLEAN, List.of(), null);
    }

    public static OrderAuditOutcome resolved() {
        return new OrderAuditOutcome(Kind.RESOLVED, List.of(), null);
    }

    public static OrderAuditOutcome issue(List<Discrepancy> discrepancies) {
        return new OrderAuditOutcome(Kind.ISSUE, discrepancies, null);
    }

    public static OrderAuditOutcome inconclusive(String reason) {
        return new OrderAuditOutcome(Kind.INCONCLUSIVE, List.of(), reason);
    }
}
