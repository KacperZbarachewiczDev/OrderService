package com.task.ing.orderaudit.application.service;

import com.task.ing.orderaudit.application.port.in.AuditQueryUseCase;
import com.task.ing.orderaudit.application.port.out.AuditIssuePort;
import com.task.ing.orderaudit.application.port.out.AuditRunPort;
import com.task.ing.orderaudit.application.port.out.PageResult;
import com.task.ing.orderaudit.application.port.out.Pagination;
import com.task.ing.orderaudit.domain.audit.AuditIssue;
import com.task.ing.orderaudit.domain.audit.AuditIssueDetail;
import com.task.ing.orderaudit.domain.audit.AuditRun;
import com.task.ing.orderaudit.domain.audit.IssueStatus;
import com.task.ing.orderaudit.domain.audit.Severity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AuditQueryService implements AuditQueryUseCase {

    private final AuditIssuePort auditIssues;
    private final AuditRunPort auditRuns;

    @Override
    public PageResult<AuditIssue> listIssues(
            IssueStatus status, Severity minimumSeverity, Pagination pagination) {
        return auditIssues.findAll(status, minimumSeverity, pagination);
    }

    @Override
    public Optional<AuditIssueDetail> issueDetail(String orderId) {
        return auditIssues.findDetail(orderId);
    }

    @Override
    public PageResult<AuditRun> listRuns(Pagination pagination) {
        return auditRuns.findAll(pagination);
    }
}
