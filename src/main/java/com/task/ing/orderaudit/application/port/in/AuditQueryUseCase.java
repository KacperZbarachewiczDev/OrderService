package com.task.ing.orderaudit.application.port.in;

import com.task.ing.orderaudit.application.port.out.PageResult;
import com.task.ing.orderaudit.application.port.out.Pagination;
import com.task.ing.orderaudit.domain.audit.AuditIssue;
import com.task.ing.orderaudit.domain.audit.AuditIssueDetail;
import com.task.ing.orderaudit.domain.audit.AuditRun;
import com.task.ing.orderaudit.domain.audit.IssueStatus;
import com.task.ing.orderaudit.domain.audit.Severity;

import java.util.Optional;

public interface AuditQueryUseCase {

    PageResult<AuditIssue> listIssues(IssueStatus status, Severity minimumSeverity, Pagination pagination);

    Optional<AuditIssueDetail> issueDetail(String orderId);

    PageResult<AuditRun> listRuns(Pagination pagination);
}
