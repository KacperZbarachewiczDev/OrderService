package com.task.ing.orderaudit.adapter.in.scheduler;

import com.task.ing.orderaudit.application.port.in.RunAuditUseCase;
import com.task.ing.orderaudit.domain.audit.AuditTrigger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class DailyAuditScheduler {

    private final RunAuditUseCase runAudit;

    @Scheduled(cron = "${audit.schedule.cron:0 0 2 * * *}", zone = "${audit.schedule.zone:UTC}")
    public void runDailyAudit() {
        try {
            runAudit.run(AuditTrigger.SCHEDULED)
                    .ifPresent(run -> log.info("Scheduled audit run #{} finished", run.id()));
        } catch (RuntimeException e) {
            log.error("Scheduled audit failed", e);
        }
    }
}
