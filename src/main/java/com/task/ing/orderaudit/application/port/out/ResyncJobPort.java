package com.task.ing.orderaudit.application.port.out;

import com.task.ing.orderaudit.domain.resync.ResyncJob;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ResyncJobPort {

    ResyncJob enqueue(String orderId, Instant now);

    Optional<ResyncJob> claim(long jobId, Instant now);

    void succeed(long jobId, int remainingDiscrepancies, Instant now);

    void fail(long jobId, String reason, Instant now);

    Optional<ResyncJob> findLatestForOrder(String orderId);

    Optional<ResyncJob> findById(long jobId);

    List<Long> findStalePendingJobIds(Instant requestedBefore, int limit);
}
