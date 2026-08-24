package com.task.ing.orderaudit.adapter.out.persistence;

import com.task.ing.orderaudit.adapter.out.persistence.entity.AuditDiscrepancyEntity;
import com.task.ing.orderaudit.adapter.out.persistence.entity.AuditIssueEntity;
import com.task.ing.orderaudit.adapter.out.persistence.repository.AuditDiscrepancyJpaRepository;
import com.task.ing.orderaudit.adapter.out.persistence.repository.AuditIssueJpaRepository;
import com.task.ing.orderaudit.application.port.out.AuditIssuePort;
import com.task.ing.orderaudit.application.port.out.PageResult;
import com.task.ing.orderaudit.application.port.out.Pagination;
import com.task.ing.orderaudit.domain.audit.AuditIssue;
import com.task.ing.orderaudit.domain.audit.AuditIssueDetail;
import com.task.ing.orderaudit.domain.audit.Discrepancy;
import com.task.ing.orderaudit.domain.audit.IssueStatus;
import com.task.ing.orderaudit.domain.audit.Severity;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class AuditIssueJpaAdapter implements AuditIssuePort {

    private final AuditIssueJpaRepository issues;
    private final AuditDiscrepancyJpaRepository discrepancies;

    @Override
    @Transactional
    public AuditIssue record(
            String orderId, List<Discrepancy> findings, Long auditRunId, Instant detectedAt) {
        AuditIssueEntity entity = issues.findByOrderId(orderId)
                .orElseGet(() -> new AuditIssueEntity(orderId, detectedAt));

        entity.setStatus(IssueStatus.OPEN);
        entity.setHighestSeverity(Discrepancy.highestSeverity(findings));
        entity.setDiscrepancyCount(findings.size());
        entity.setLastDetectedAt(detectedAt);
        entity.setResolvedAt(null);
        entity.setLastAuditRunId(auditRunId);
        AuditIssueEntity saved = issues.saveAndFlush(entity);

        discrepancies.deleteByIssueId(saved.getId());
        discrepancies.flush();
        discrepancies.saveAll(findings.stream()
                .map(finding -> new AuditDiscrepancyEntity(
                        saved.getId(),
                        auditRunId,
                        finding.type(),
                        finding.severity(),
                        finding.field(),
                        finding.expected(),
                        finding.actual(),
                        detectedAt))
                .toList());
        return PersistenceMapper.toDomain(saved);
    }

    @Override
    @Transactional
    public boolean resolve(String orderId, Instant resolvedAt) {
        Optional<AuditIssueEntity> existing = issues.findByOrderId(orderId);
        if (existing.isEmpty() || existing.get().getStatus() == IssueStatus.RESOLVED) {
            return false;
        }
        AuditIssueEntity entity = existing.get();
        entity.setStatus(IssueStatus.RESOLVED);
        entity.setResolvedAt(resolvedAt);
        entity.setDiscrepancyCount(0);
        issues.save(entity);
        discrepancies.deleteByIssueId(entity.getId());
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AuditIssueDetail> findDetail(String orderId) {
        return issues.findByOrderId(orderId).map(entity -> new AuditIssueDetail(
                PersistenceMapper.toDomain(entity),
                discrepancies.findByIssueIdOrderByIdAsc(entity.getId()).stream()
                        .map(PersistenceMapper::toDomain)
                        .toList()));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<AuditIssue> findAll(
            IssueStatus status, Severity minimumSeverity, Pagination pagination) {
        Collection<IssueStatus> statuses = status == null
                ? Arrays.asList(IssueStatus.values())
                : List.of(status);
        Collection<Severity> severities = Arrays.stream(Severity.values())
                .filter(severity -> minimumSeverity == null || severity.isAtLeast(minimumSeverity))
                .toList();

        Page<AuditIssueEntity> page = issues.search(
                statuses, severities, PageRequest.of(pagination.page(), pagination.size()));
        return new PageResult<>(
                page.getContent().stream().map(PersistenceMapper::toDomain).toList(),
                pagination.page(),
                pagination.size(),
                page.getTotalElements());
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> findOpenOrderIds(int limit, String afterOrderId) {
        return issues.findOpenOrderIds(afterOrderId, PageRequest.ofSize(limit));
    }

    @Override
    @Transactional(readOnly = true)
    public long countOpen() {
        return issues.countByStatus(IssueStatus.OPEN);
    }
}
