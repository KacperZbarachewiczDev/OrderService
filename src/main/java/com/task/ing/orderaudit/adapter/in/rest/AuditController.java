package com.task.ing.orderaudit.adapter.in.rest;

import com.task.ing.orderaudit.adapter.in.rest.dto.AuditIssueDetailResponse;
import com.task.ing.orderaudit.adapter.in.rest.dto.AuditIssueResponse;
import com.task.ing.orderaudit.adapter.in.rest.dto.AuditRunResponse;
import com.task.ing.orderaudit.adapter.in.rest.dto.PageResponse;
import com.task.ing.orderaudit.adapter.in.rest.dto.ResyncJobResponse;
import com.task.ing.orderaudit.application.port.in.AuditQueryUseCase;
import com.task.ing.orderaudit.application.port.in.ResyncOrderUseCase;
import com.task.ing.orderaudit.application.port.in.RunAuditUseCase;
import com.task.ing.orderaudit.application.port.out.Pagination;
import com.task.ing.orderaudit.domain.audit.AuditTrigger;
import com.task.ing.orderaudit.domain.audit.IssueStatus;
import com.task.ing.orderaudit.domain.audit.Severity;
import com.task.ing.orderaudit.domain.resync.ResyncJob;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditQueryUseCase auditQueries;
    private final ResyncOrderUseCase resync;
    private final RunAuditUseCase runAudit;

    @GetMapping("/issues")
    public PageResponse<AuditIssueResponse> listIssues(
            @RequestParam(defaultValue = "OPEN") String status,
            @RequestParam(required = false) Severity minSeverity,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(500) int size) {
        IssueStatus issueStatus = "ALL".equalsIgnoreCase(status) ? null : IssueStatus.valueOf(status.toUpperCase());
        return PageResponse.from(
                auditQueries.listIssues(issueStatus, minSeverity, Pagination.of(page, size)),
                AuditIssueResponse::from);
    }

    @GetMapping("/issues/{orderId}")
    public AuditIssueDetailResponse issueDetail(@PathVariable String orderId) {
        return auditQueries.issueDetail(orderId)
                .map(detail -> AuditIssueDetailResponse.from(detail, resync.resyncStatus(orderId)))
                .orElseThrow(() -> new IssueNotFoundException(orderId));
    }

    @PostMapping("/issues/{orderId}/resync")
    public ResponseEntity<ResyncJobResponse> requestResync(@PathVariable String orderId) {
        ResyncJob job = resync.requestResync(orderId);
        return ResponseEntity.accepted().body(ResyncJobResponse.from(job));
    }

    @GetMapping("/issues/{orderId}/resync")
    public ResyncJobResponse resyncStatus(@PathVariable String orderId) {
        Optional<ResyncJob> job = resync.resyncStatus(orderId);
        return job.map(ResyncJobResponse::from).orElseThrow(() -> new IssueNotFoundException(orderId));
    }

    @GetMapping("/runs")
    public PageResponse<AuditRunResponse> listRuns(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(500) int size) {
        return PageResponse.from(auditQueries.listRuns(Pagination.of(page, size)), AuditRunResponse::from);
    }

    @PostMapping("/runs")
    public ResponseEntity<AuditRunResponse> triggerRun() {
        return runAudit.run(AuditTrigger.MANUAL)
                .map(run -> ResponseEntity.ok(AuditRunResponse.from(run)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT).build());
    }
}
