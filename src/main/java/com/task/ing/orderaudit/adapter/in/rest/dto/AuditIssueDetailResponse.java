package com.task.ing.orderaudit.adapter.in.rest.dto;

import com.task.ing.orderaudit.domain.audit.AuditIssueDetail;
import com.task.ing.orderaudit.domain.resync.ResyncJob;

import java.util.List;
import java.util.Optional;

public record AuditIssueDetailResponse(
        AuditIssueResponse issue,
        List<DiscrepancyResponse> discrepancies,
        ResyncJobResponse latestResync) {
    public static AuditIssueDetailResponse from(AuditIssueDetail detail, Optional<ResyncJob> latestResync) {
        return new AuditIssueDetailResponse(
                AuditIssueResponse.from(detail.issue()),
                detail.discrepancies().stream().map(DiscrepancyResponse::from).toList(),
                latestResync.map(ResyncJobResponse::from).orElse(null));
    }
}
