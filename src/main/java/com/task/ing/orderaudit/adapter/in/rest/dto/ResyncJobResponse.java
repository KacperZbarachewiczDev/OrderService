package com.task.ing.orderaudit.adapter.in.rest.dto;

import com.task.ing.orderaudit.domain.resync.ResyncJob;

import java.time.Instant;

public record ResyncJobResponse(
        Long jobId,
        String orderId,
        String status,
        Instant requestedAt,
        Instant startedAt,
        Instant finishedAt,
        int attempts,
        Integer remainingDiscrepancies,
        String failureReason) {
    public static ResyncJobResponse from(ResyncJob job) {
        return new ResyncJobResponse(
                job.id(),
                job.orderId(),
                job.status().name(),
                job.requestedAt(),
                job.startedAt(),
                job.finishedAt(),
                job.attempts(),
                job.remainingDiscrepancies(),
                job.failureReason());
    }
}
