package com.task.ing.orderaudit.adapter.out.persistence;

import com.task.ing.orderaudit.adapter.out.persistence.entity.ResyncJobEntity;
import com.task.ing.orderaudit.adapter.out.persistence.repository.ResyncJobJpaRepository;
import com.task.ing.orderaudit.application.port.out.ResyncJobPort;
import com.task.ing.orderaudit.domain.resync.ResyncJob;
import com.task.ing.orderaudit.domain.resync.ResyncStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class ResyncJobJpaAdapter implements ResyncJobPort {

    private final ResyncJobJpaRepository repository;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ResyncJob enqueue(String orderId, Instant now) {
        repository.insertIfNoneActive(orderId, now);
        return repository.findActive(orderId)
                .map(PersistenceMapper::toDomain)
                .orElseThrow(() -> new IllegalStateException(
                        "resync job for order " + orderId + " vanished right after being enqueued"));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ResyncJob> claim(long jobId, Instant now) {
        if (repository.claim(jobId, now) != 1) {
            return Optional.empty();
        }
        return repository.findById(jobId).map(PersistenceMapper::toDomain);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void succeed(long jobId, int remainingDiscrepancies, Instant now) {
        repository.findById(jobId).ifPresent(entity -> {
            entity.setStatus(ResyncStatus.SUCCEEDED);
            entity.setFinishedAt(now);
            entity.setRemainingDiscrepancies(remainingDiscrepancies);
            entity.setFailureReason(null);
            repository.save(entity);
        });
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(long jobId, String reason, Instant now) {
        repository.findById(jobId).ifPresent(entity -> {
            entity.setStatus(ResyncStatus.FAILED);
            entity.setFinishedAt(now);
            entity.setFailureReason(reason);
            repository.save(entity);
        });
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ResyncJob> findLatestForOrder(String orderId) {
        return repository.findFirstByOrderIdOrderByRequestedAtDescIdDesc(orderId)
                .map(PersistenceMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ResyncJob> findById(long jobId) {
        return repository.findById(jobId).map(PersistenceMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> findStalePendingJobIds(Instant requestedBefore, int limit) {
        return repository.findStalePendingIds(requestedBefore, PageRequest.ofSize(limit));
    }
}
