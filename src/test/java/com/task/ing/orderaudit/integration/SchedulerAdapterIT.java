package com.task.ing.orderaudit.integration;

import com.task.ing.orderaudit.adapter.in.scheduler.DailyAuditScheduler;
import com.task.ing.orderaudit.adapter.in.scheduler.OutboxDispatchScheduler;
import com.task.ing.orderaudit.adapter.in.scheduler.ResyncRecoveryScheduler;
import com.task.ing.orderaudit.application.port.out.SchedulerLockPort;
import com.task.ing.orderaudit.support.AbstractIntegrationTest;
import com.task.ing.orderaudit.support.Payloads;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class SchedulerAdapterIT extends AbstractIntegrationTest {

    @Autowired
    private DailyAuditScheduler dailyAuditScheduler;

    @Autowired
    private OutboxDispatchScheduler outboxDispatchScheduler;

    @Autowired
    private ResyncRecoveryScheduler resyncRecoveryScheduler;

    @Autowired
    private SchedulerLockPort schedulerLock;

    @Test
    @DisplayName("the nightly trigger really runs an audit")
    void the_daily_trigger_runs_an_audit() {
        orderService.modifiedSince();
        paymentService.modifiedSince();

        dailyAuditScheduler.runDailyAudit();

        assertThat(count("audit_runs")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select trigger_type from audit_runs", String.class)).isEqualTo("SCHEDULED");
    }

    @Test
    @DisplayName("the nightly trigger stands down when another node is already running the audit")
    void the_daily_trigger_respects_the_cluster_lock() {
        schedulerLock.acquire("daily-audit", "another-node", Instant.now(), Duration.ofMinutes(10));

        dailyAuditScheduler.runDailyAudit();

        assertThat(count("audit_runs")).isZero();
    }

    @Test
    @DisplayName("a failing audit does not take the trigger down with it")
    void the_daily_trigger_survives_a_failure() {
        orderService.modifiedSinceUnavailable();
        paymentService.modifiedSinceUnavailable();

        assertThatCode(() -> dailyAuditScheduler.runDailyAudit()).doesNotThrowAnyException();

        assertThat(count("audit_runs")).isEqualTo(1);
    }

    @Test
    @DisplayName("the outbox trigger really delivers what the audit queued")
    void the_outbox_trigger_delivers_notifications() {
        String orderId = "ORD-SCH1";
        givenReceivedOrderEvent(Payloads.orderEvent("EVT-SCH1-1", orderId, "ORDER_CREATED", Payloads.T0));
        givenReceivedOrderEvent(Payloads.orderEvent("EVT-SCH1-2", orderId, "ORDER_COMPLETED", Payloads.T2));
        orderService.modifiedSince(orderId);
        orderService.aggregate(orderId, Payloads.order(orderId, "CANCELLED", "150.00"));
        orderService.eventCount(orderId, 2);
        orderService.events(orderId,
                Payloads.orderEvent("EVT-SCH1-1", orderId, "ORDER_CREATED", Payloads.T0),
                Payloads.orderEvent("EVT-SCH1-2", orderId, "ORDER_COMPLETED", Payloads.T2));
        paymentService.modifiedSince();
        paymentService.aggregateMissing(orderId);
        paymentService.eventCount(orderId, 0);
        paymentService.events(orderId);

        dailyAuditScheduler.runDailyAudit();
        outboxDispatchScheduler.dispatch();

        assertThat(SMTP.getReceivedMessages()).hasSize(1);
    }

    @Test
    @DisplayName("the outbox trigger is a no-op when there is nothing queued")
    void the_outbox_trigger_does_nothing_when_the_outbox_is_empty() {
        assertThatCode(() -> outboxDispatchScheduler.dispatch()).doesNotThrowAnyException();

        assertThat(SMTP.getReceivedMessages()).isEmpty();
    }

    @Test
    @DisplayName("a mail server outage does not take the outbox trigger down")
    void the_outbox_trigger_survives_a_mail_failure() {
        String orderId = "ORD-SCH3";
        givenReceivedOrderEvent(Payloads.orderEvent("EVT-SCH3-1", orderId, "ORDER_CREATED", Payloads.T0));
        givenReceivedOrderEvent(Payloads.orderEvent("EVT-SCH3-2", orderId, "ORDER_COMPLETED", Payloads.T2));
        orderService.modifiedSince(orderId);
        orderService.aggregate(orderId, Payloads.order(orderId, "CANCELLED", "150.00"));
        orderService.eventCount(orderId, 2);
        orderService.events(orderId,
                Payloads.orderEvent("EVT-SCH3-1", orderId, "ORDER_CREATED", Payloads.T0),
                Payloads.orderEvent("EVT-SCH3-2", orderId, "ORDER_COMPLETED", Payloads.T2));
        paymentService.modifiedSince();
        paymentService.aggregateMissing(orderId);
        paymentService.eventCount(orderId, 0);
        paymentService.events(orderId);
        dailyAuditScheduler.runDailyAudit();

        SMTP.stop();
        try {
            assertThatCode(() -> outboxDispatchScheduler.dispatch()).doesNotThrowAnyException();
        } finally {
            SMTP.start();
        }

        assertThat(jdbcTemplate.queryForObject(
                "select status from notification_outbox", String.class)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("the recovery trigger picks up a repair that was never executed")
    void the_recovery_trigger_re_dispatches_a_stuck_repair() {
        String orderId = "ORD-SCH2";
        orderService.aggregate(orderId, Payloads.order(orderId, "COMPLETED", "150.00"));
        orderService.events(orderId,
                Payloads.orderEvent("EVT-SCH2-1", orderId, "ORDER_CREATED", Payloads.T0));
        orderService.eventCount(orderId, 1);
        paymentService.aggregateMissing(orderId);
        paymentService.events(orderId);
        paymentService.eventCount(orderId, 0);

        jdbcTemplate.update("""
                insert into resync_jobs (order_id, status, requested_at, attempts)
                values (?, 'PENDING', now() - interval '10 minutes', 0)
                """, orderId);

        resyncRecoveryScheduler.recover();

        awaitAssertion(() -> assertThat(jdbcTemplate.queryForObject(
                "select status from resync_jobs where order_id = ?", String.class, orderId))
                .isEqualTo("SUCCEEDED"));
    }

    @Test
    @DisplayName("the recovery trigger is a no-op when nothing is stuck")
    void the_recovery_trigger_does_nothing_when_all_is_well() {
        assertThatCode(() -> resyncRecoveryScheduler.recover()).doesNotThrowAnyException();

        assertThat(count("resync_jobs")).isZero();
    }
}
