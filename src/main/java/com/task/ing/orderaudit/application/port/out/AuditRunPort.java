package com.task.ing.orderaudit.application.port.out;

import com.task.ing.orderaudit.domain.audit.AuditRun;
import com.task.ing.orderaudit.domain.audit.AuditRunStats;
import com.task.ing.orderaudit.domain.audit.AuditTrigger;

import java.time.Instant;
import java.util.Optional;

public interface AuditRunPort {

    AuditRun start(AuditTrigger trigger, Instant windowFrom, Instant windowTo, Instant startedAt);

    AuditRun complete(long runId, AuditRunStats stats, Instant finishedAt);

    void fail(long runId, String reason, Instant finishedAt);

    Optional<Instant> lastCompletedWindowEnd();

    Optional<AuditRun> findById(long runId);

    PageResult<AuditRun> findAll(Pagination pagination);
}
