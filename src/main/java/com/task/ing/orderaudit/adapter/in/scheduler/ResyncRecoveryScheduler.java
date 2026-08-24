package com.task.ing.orderaudit.adapter.in.scheduler;

import com.task.ing.orderaudit.application.port.in.ResyncOrderUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class ResyncRecoveryScheduler {

    private final ResyncOrderUseCase resync;

    @Scheduled(
            fixedDelayString = "${audit.schedule.resync-recovery-delay:PT30S}",
            initialDelayString = "${audit.schedule.resync-recovery-initial-delay:PT30S}")
    public void recover() {
        try {
            resync.recoverStaleJobs();
        } catch (RuntimeException e) {
            log.error("Resync recovery pass failed", e);
        }
    }
}
