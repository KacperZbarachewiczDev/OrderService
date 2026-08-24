package com.task.ing.orderaudit.integration;

import com.task.ing.orderaudit.application.port.in.DispatchNotificationsUseCase;
import com.task.ing.orderaudit.application.port.in.RunAuditUseCase;
import com.task.ing.orderaudit.domain.audit.AuditRun;
import com.task.ing.orderaudit.domain.audit.AuditTrigger;
import com.task.ing.orderaudit.support.AbstractIntegrationTest;
import com.task.ing.orderaudit.support.Payloads;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuditLifecycleIT extends AbstractIntegrationTest {

    @Autowired
    private RunAuditUseCase runAudit;

    @Autowired
    private DispatchNotificationsUseCase dispatchNotifications;

    @Test
    @DisplayName("an open issue is re-checked even when neither system reports a change")
    void keeps_re_checking_a_known_problem() {
        String orderId = "ORD-L1";
        givenLocallyCompleted(orderId);
        givenSourceCancelled(orderId);

        runAudit();
        assertThat(issueStatus(orderId)).isEqualTo("OPEN");

        orderService.modifiedSince();
        paymentService.modifiedSince();

        AuditRun second = runAudit();

        assertThat(second.stats().ordersWithIssues()).isEqualTo(1);
        assertThat(issueStatus(orderId)).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("a problem that comes back reopens the same issue rather than creating a second one")
    void reopens_an_issue_that_breaks_again() {
        String orderId = "ORD-L2";
        givenLocallyCompleted(orderId);
        givenSourceCancelled(orderId);
        runAudit();

        orderService.aggregate(orderId, Payloads.order(orderId, "COMPLETED", "150.00"));
        runAudit();
        assertThat(issueStatus(orderId)).isEqualTo("RESOLVED");

        orderService.aggregate(orderId, Payloads.order(orderId, "CANCELLED", "150.00"));
        runAudit();

        assertThat(issueStatus(orderId)).isEqualTo("OPEN");
        assertThat(count("audit_issues")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select resolved_at from audit_issues where order_id = ?", java.sql.Timestamp.class, orderId))
                .isNull();
    }

    @Test
    @DisplayName("a resolved order that stays consistent is not reported again")
    void stops_reporting_a_fixed_order() {
        String orderId = "ORD-L3";
        givenLocallyCompleted(orderId);
        givenSourceCancelled(orderId);
        runAudit();

        orderService.aggregate(orderId, Payloads.order(orderId, "COMPLETED", "150.00"));
        runAudit();
        AuditRun third = runAudit();

        assertThat(third.stats().ordersWithIssues()).isZero();
        assertThat(third.stats().ordersResolved()).isZero();
        assertThat(issueStatus(orderId)).isEqualTo("RESOLVED");
    }

    @Test
    @DisplayName("an order changed only in this service is audited too")
    void audits_orders_that_only_changed_locally() {
        String orderId = "ORD-L4";
        givenLocallyCompleted(orderId);

        orderService.modifiedSince();
        orderService.aggregateMissing(orderId);
        orderService.eventCount(orderId, 0);
        orderService.events(orderId);
        paymentService.modifiedSince();
        paymentService.aggregateMissing(orderId);
        paymentService.eventCount(orderId, 0);
        paymentService.events(orderId);

        AuditRun run = runAudit();

        assertThat(run.stats().ordersWithIssues()).isEqualTo(1);
        assertThat(discrepancyTypes(orderId)).contains("ORDER_MISSING_IN_SOURCE");
    }

    @Test
    @DisplayName("each run gets its own row in the history")
    void records_every_run() {
        orderService.modifiedSince();
        paymentService.modifiedSince();

        runAudit();
        runAudit();
        runAudit();

        assertThat(count("audit_runs")).isEqualTo(3);
    }

    @Test
    @DisplayName("a run started by hand is recorded as such")
    void distinguishes_a_manual_run() {
        orderService.modifiedSince();
        paymentService.modifiedSince();

        AuditRun run = runAudit();

        assertThat(jdbcTemplate.queryForObject(
                "select trigger_type from audit_runs where id = ?", String.class, run.id()))
                .isEqualTo("MANUAL");
    }

    @Test
    @DisplayName("a run started by the schedule is recorded as such")
    void distinguishes_a_scheduled_run() {
        orderService.modifiedSince();
        paymentService.modifiedSince();

        AuditRun run = runAudit.run(AuditTrigger.SCHEDULED).orElseThrow();

        assertThat(jdbcTemplate.queryForObject(
                "select trigger_type from audit_runs where id = ?", String.class, run.id()))
                .isEqualTo("SCHEDULED");
    }

    @Test
    @DisplayName("every run that finds something queues its own notification")
    void notifies_once_per_run_with_findings() {
        String orderId = "ORD-L5";
        givenLocallyCompleted(orderId);
        givenSourceCancelled(orderId);

        runAudit();
        runAudit();

        assertThat(count("notification_outbox")).isEqualTo(2);
        assertThat(dispatchNotifications.dispatchDue()).isEqualTo(2);
        assertThat(SMTP.getReceivedMessages()).hasSize(2);
    }

    @Test
    @DisplayName("the counters add up across a run with several kinds of outcome")
    void counts_every_outcome_of_one_run() {
        givenLocallyCompleted("ORD-L6-CLEAN");
        givenLocallyCompleted("ORD-L6-BROKEN");
        givenLocallyCompleted("ORD-L6-DOWN");

        orderService.modifiedSince("ORD-L6-CLEAN", "ORD-L6-BROKEN", "ORD-L6-DOWN");
        stubCompletedOrder("ORD-L6-CLEAN");
        orderService.aggregate("ORD-L6-BROKEN", Payloads.order("ORD-L6-BROKEN", "CANCELLED", "150.00"));
        orderService.eventCount("ORD-L6-BROKEN", 2);
        orderService.events("ORD-L6-BROKEN", sourceEvents("ORD-L6-BROKEN"));
        orderService.aggregateUnavailable("ORD-L6-DOWN");

        paymentService.modifiedSince();
        List.of("ORD-L6-CLEAN", "ORD-L6-BROKEN", "ORD-L6-DOWN").forEach(orderId -> {
            paymentService.aggregateMissing(orderId);
            paymentService.eventCount(orderId, 0);
            paymentService.events(orderId);
        });

        AuditRun run = runAudit();

        assertThat(run.stats().ordersChecked()).isEqualTo(2);
        assertThat(run.stats().ordersWithIssues()).isEqualTo(1);
        assertThat(run.stats().ordersInconclusive()).isEqualTo(1);
        assertThat(run.stats().discrepanciesFound()).isPositive();
    }

    @Test
    @DisplayName("an order the audit could not verify is re-checked next time")
    void retries_an_inconclusive_order_on_the_next_run() {
        String orderId = "ORD-L7";
        givenLocallyCompleted(orderId);
        orderService.modifiedSince(orderId);
        orderService.aggregateUnavailable(orderId);
        paymentService.modifiedSince();
        paymentService.aggregateMissing(orderId);
        paymentService.eventCount(orderId, 0);
        paymentService.events(orderId);

        assertThat(runAudit().stats().ordersInconclusive()).isEqualTo(1);

        givenSourceCancelled(orderId);
        AuditRun second = runAudit();

        assertThat(second.stats().ordersWithIssues()).isEqualTo(1);
        assertThat(issueStatus(orderId)).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("the window of a run covers exactly the gap since the previous one")
    void leaves_no_gap_between_runs() {
        orderService.modifiedSince();
        paymentService.modifiedSince();

        AuditRun first = runAudit();
        AuditRun second = runAudit();
        AuditRun third = runAudit();

        assertThat(second.windowFrom()).isEqualTo(first.windowTo());
        assertThat(third.windowFrom()).isEqualTo(second.windowTo());
    }

    @Test
    @DisplayName("the very first run reaches back over the configured look-back period")
    void looks_back_on_the_first_ever_run() {
        orderService.modifiedSince();
        paymentService.modifiedSince();

        AuditRun first = runAudit();

        assertThat(first.windowFrom()).isBefore(first.windowTo().minusSeconds(86_400 * 29));
    }

    private AuditRun runAudit() {
        return runAudit.run(AuditTrigger.MANUAL).orElseThrow();
    }

    private void givenLocallyCompleted(String orderId) {
        givenReceivedOrderEvent(Payloads.orderEvent("EVT-" + orderId + "-1", orderId, "ORDER_CREATED", Payloads.T0));
        givenReceivedOrderEvent(Payloads.orderEvent("EVT-" + orderId + "-2", orderId, "ORDER_COMPLETED", Payloads.T2));
    }

    private void givenSourceCancelled(String orderId) {
        orderService.modifiedSince(orderId);
        orderService.aggregate(orderId, Payloads.order(orderId, "CANCELLED", "150.00"));
        orderService.eventCount(orderId, 2);
        orderService.events(orderId, sourceEvents(orderId));
        paymentService.modifiedSince();
        paymentService.aggregateMissing(orderId);
        paymentService.eventCount(orderId, 0);
        paymentService.events(orderId);
    }

    private void stubCompletedOrder(String orderId) {
        orderService.aggregate(orderId, Payloads.order(orderId, "COMPLETED", "150.00"));
        orderService.eventCount(orderId, 2);
        orderService.events(orderId, sourceEvents(orderId));
    }

    private String[] sourceEvents(String orderId) {
        return new String[]{
                Payloads.orderEvent("EVT-" + orderId + "-1", orderId, "ORDER_CREATED", Payloads.T0),
                Payloads.orderEvent("EVT-" + orderId + "-2", orderId, "ORDER_COMPLETED", Payloads.T2)};
    }

    private String issueStatus(String orderId) {
        return jdbcTemplate.queryForObject(
                "select status from audit_issues where order_id = ?", String.class, orderId);
    }

    private List<String> discrepancyTypes(String orderId) {
        return jdbcTemplate.queryForList("""
                select d.type from audit_discrepancies d
                join audit_issues i on i.id = d.issue_id
                where i.order_id = ?
                """, String.class, orderId);
    }
}
