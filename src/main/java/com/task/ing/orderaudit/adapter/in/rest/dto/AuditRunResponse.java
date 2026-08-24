package com.task.ing.orderaudit.adapter.in.rest.dto;

import com.task.ing.orderaudit.domain.audit.AuditRun;

import java.time.Instant;

public record AuditRunResponse(
        Long id,
        String trigger,
        String status,
        Instant windowFrom,
        Instant windowTo,
        Instant startedAt,
        Instant finishedAt,
        int ordersChecked,
        int ordersWithIssues,
        int ordersResolved,
        int discrepanciesFound,
        int ordersInconclusive,
        String failureReason) {
    public static AuditRunResponse from(AuditRun run) {
        return new AuditRunResponse(
                run.id(),
                run.trigger().name(),
                run.status().name(),
                run.windowFrom(),
                run.windowTo(),
                run.startedAt(),
                run.finishedAt(),
                run.stats().ordersChecked(),
                run.stats().ordersWithIssues(),
                run.stats().ordersResolved(),
                run.stats().discrepanciesFound(),
                run.stats().ordersInconclusive(),
                run.failureReason());
    }
}
