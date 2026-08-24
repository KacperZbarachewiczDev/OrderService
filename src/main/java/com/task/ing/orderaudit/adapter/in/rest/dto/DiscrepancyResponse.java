package com.task.ing.orderaudit.adapter.in.rest.dto;

import com.task.ing.orderaudit.domain.audit.Discrepancy;

public record DiscrepancyResponse(
        String type, String severity, String field, String expected, String actual, String description) {
    public static DiscrepancyResponse from(Discrepancy discrepancy) {
        return new DiscrepancyResponse(
                discrepancy.type().name(),
                discrepancy.severity().name(),
                discrepancy.field(),
                discrepancy.expected(),
                discrepancy.actual(),
                discrepancy.describe());
    }
}
