package com.task.ing.orderaudit.domain.audit;

public record AuditRunStats(
        int ordersChecked,
        int ordersWithIssues,
        int ordersResolved,
        int discrepanciesFound,
        int ordersInconclusive) {
    public static AuditRunStats empty() {
        return new AuditRunStats(0, 0, 0, 0, 0);
    }

    public AuditRunStats withChecked() {
        return new AuditRunStats(
                ordersChecked + 1, ordersWithIssues, ordersResolved, discrepanciesFound, ordersInconclusive);
    }

    public AuditRunStats withIssue(int discrepancies) {
        return new AuditRunStats(
                ordersChecked + 1, ordersWithIssues + 1, ordersResolved,
                discrepanciesFound + discrepancies, ordersInconclusive);
    }

    public AuditRunStats withResolved() {
        return new AuditRunStats(
                ordersChecked + 1, ordersWithIssues, ordersResolved + 1, discrepanciesFound, ordersInconclusive);
    }

    public AuditRunStats withInconclusive() {
        return new AuditRunStats(
                ordersChecked, ordersWithIssues, ordersResolved, discrepanciesFound, ordersInconclusive + 1);
    }

    public boolean hasFindings() {
        return ordersWithIssues > 0;
    }
}
