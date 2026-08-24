package com.task.ing.orderaudit.application.port.in;

import com.task.ing.orderaudit.domain.resync.ResyncJob;

import java.util.Optional;

public interface ResyncOrderUseCase {

    ResyncJob requestResync(String orderId);

    Optional<ResyncJob> resyncStatus(String orderId);

    int recoverStaleJobs();
}
