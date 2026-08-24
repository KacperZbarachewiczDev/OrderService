package com.task.ing.orderaudit.application.port.out;

import com.task.ing.orderaudit.domain.audit.AuditIssue;
import com.task.ing.orderaudit.domain.audit.AuditIssueDetail;
import com.task.ing.orderaudit.domain.audit.Discrepancy;
import com.task.ing.orderaudit.domain.audit.IssueStatus;
import com.task.ing.orderaudit.domain.audit.Severity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AuditIssuePort {

    AuditIssue record(String orderId, List<Discrepancy> discrepancies, Long auditRunId, Instant detectedAt);

    boolean resolve(String orderId, Instant resolvedAt);

    Optional<AuditIssueDetail> findDetail(String orderId);

    PageResult<AuditIssue> findAll(IssueStatus status, Severity minimumSeverity, Pagination pagination);

    List<String> findOpenOrderIds(int limit, String afterOrderId);

    long countOpen();
}
