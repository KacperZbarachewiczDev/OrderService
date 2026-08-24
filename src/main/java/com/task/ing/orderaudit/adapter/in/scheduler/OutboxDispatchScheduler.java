package com.task.ing.orderaudit.adapter.in.scheduler;

import com.task.ing.orderaudit.application.port.in.DispatchNotificationsUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class OutboxDispatchScheduler {

    private final DispatchNotificationsUseCase dispatchNotifications;

    @Scheduled(
            fixedDelayString = "${audit.schedule.outbox-delay:PT10S}",
            initialDelayString = "${audit.schedule.outbox-initial-delay:PT5S}")
    public void dispatch() {
        try {
            int sent = dispatchNotifications.dispatchDue();
            if (sent > 0) {
                log.info("Delivered {} audit notification(s)", sent);
            }
        } catch (RuntimeException e) {
            log.error("Outbox dispatch pass failed", e);
        }
    }
}
