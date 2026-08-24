package com.task.ing.orderaudit.adapter.out.persistence;

import com.task.ing.orderaudit.adapter.out.persistence.entity.AuditRunEntity;
import com.task.ing.orderaudit.adapter.out.persistence.repository.AuditRunJpaRepository;
import com.task.ing.orderaudit.application.port.out.AuditRunPort;
import com.task.ing.orderaudit.application.port.out.PageResult;
import com.task.ing.orderaudit.application.port.out.Pagination;
import com.task.ing.orderaudit.domain.audit.AuditRun;
import com.task.ing.orderaudit.domain.audit.AuditRunStats;
import com.task.ing.orderaudit.domain.audit.AuditRunStatus;
import com.task.ing.orderaudit.domain.audit.AuditTrigger;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class AuditRunJpaAdapter implements AuditRunPort {

    private final AuditRunJpaRepository repository;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditRun start(AuditTrigger trigger, Instant windowFrom, Instant windowTo, Instant startedAt) {
        AuditRunEntity entity = repository.save(
                new AuditRunEntity(trigger, windowFrom, windowTo, startedAt));
        return PersistenceMapper.toDomain(entity);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditRun complete(long runId, AuditRunStats stats, Instant finishedAt) {
        AuditRunEntity entity = repository.findById(runId)
                .orElseThrow(() -> new IllegalStateException("audit run " + runId + " disappeared"));
        entity.setStatus(AuditRunStatus.COMPLETED);
        entity.setFinishedAt(finishedAt);
        entity.setOrdersChecked(stats.ordersChecked());
        entity.setOrdersWithIssues(stats.ordersWithIssues());
        entity.setOrdersResolved(stats.ordersResolved());
        entity.setDiscrepanciesFound(stats.discrepanciesFound());
        entity.setOrdersInconclusive(stats.ordersInconclusive());
        return PersistenceMapper.toDomain(repository.save(entity));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(long runId, String reason, Instant finishedAt) {
        repository.findById(runId).ifPresent(entity -> {
            entity.setStatus(AuditRunStatus.FAILED);
            entity.setFinishedAt(finishedAt);
            entity.setFailureReason(truncate(reason));
            repository.save(entity);
        });
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Instant> lastCompletedWindowEnd() {
        List<Instant> latest = repository.findLatestCompletedWindowEnd(PageRequest.ofSize(1));
        return latest.stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AuditRun> findById(long runId) {
        return repository.findById(runId).map(PersistenceMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<AuditRun> findAll(Pagination pagination) {
        Page<AuditRunEntity> page = repository.findAllByOrderByStartedAtDesc(
                PageRequest.of(pagination.page(), pagination.size()));
        return new PageResult<>(
                page.getContent().stream().map(PersistenceMapper::toDomain).toList(),
                pagination.page(),
                pagination.size(),
                page.getTotalElements());
    }

    private String truncate(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= 4000 ? reason : reason.substring(0, 4000);
    }
}
