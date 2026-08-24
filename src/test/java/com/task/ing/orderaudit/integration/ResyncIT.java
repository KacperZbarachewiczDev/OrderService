package com.task.ing.orderaudit.integration;

import com.task.ing.orderaudit.application.port.in.ResyncOrderUseCase;
import com.task.ing.orderaudit.application.port.in.RunAuditUseCase;
import com.task.ing.orderaudit.domain.audit.AuditTrigger;
import com.task.ing.orderaudit.domain.resync.ResyncJob;
import com.task.ing.orderaudit.domain.resync.ResyncStatus;
import com.task.ing.orderaudit.support.AbstractIntegrationTest;
import com.task.ing.orderaudit.support.Payloads;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ResyncIT extends AbstractIntegrationTest {

    @Autowired
    private ResyncOrderUseCase resync;

    @Autowired
    private RunAuditUseCase runAudit;

    @Test
    @DisplayName("resynchronising pulls in the lost events and closes the issue")
    void repairs_an_order_that_lost_events() {
        String orderId = "ORD-R1";
        givenLocallyOnlyTheCreationEvent(orderId);
        givenSourceHasEverything(orderId, "COMPLETED", "150.00");

        runAudit.run(AuditTrigger.MANUAL);
        assertThat(issue(orderId).get("status")).isEqualTo("OPEN");

        resync.requestResync(orderId);

        awaitAssertion(() -> assertThat(job(orderId).status()).isEqualTo(ResyncStatus.SUCCEEDED));
        assertThat(job(orderId).remainingDiscrepancies()).isZero();
        assertThat(issue(orderId).get("status")).isEqualTo("RESOLVED");
        assertThat(localEventIds(orderId)).containsExactlyInAnyOrder(
                "EVT-R1-1", "EVT-R1-2", "EVT-R1-3");
        assertThat(order(orderId).get("status")).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("events pulled in by a repair are marked as such, not passed off as stream traffic")
    void records_where_the_recovered_events_came_from() {
        String orderId = "ORD-R2";
        givenLocallyOnlyTheCreationEvent(orderId);
        givenSourceHasEverything(orderId, "COMPLETED", "150.00");

        resync.requestResync(orderId);
        awaitAssertion(() -> assertThat(job(orderId).status()).isEqualTo(ResyncStatus.SUCCEEDED));

        List<Map<String, Object>> events = jdbcTemplate.queryForList(
                "select event_id, origin, payload::text as payload from ingested_events where order_id = ? order by event_id",
                orderId);
        assertThat(events).hasSize(3);
        assertThat(events)
                .filteredOn(row -> row.get("event_id").equals("EVT-R2-1"))
                .singleElement()
                .satisfies(row -> assertThat(row.get("origin")).isEqualTo("KAFKA"));
        assertThat(events)
                .filteredOn(row -> row.get("event_id").equals("EVT-R2-2"))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.get("origin")).isEqualTo("RESYNC");

                    assertThat((String) row.get("payload")).contains("ORDER_PAID");
                });
    }

    @Test
    @DisplayName("asking twice returns the same job instead of starting a second rebuild")
    void is_idempotent_while_a_job_is_in_flight() {
        String orderId = "ORD-R3";
        givenSourceHasEverything(orderId, "COMPLETED", "150.00");

        ResyncJob first = resync.requestResync(orderId);
        ResyncJob second = resync.requestResync(orderId);

        assertThat(second.id()).isEqualTo(first.id());
        awaitAssertion(() -> assertThat(job(orderId).status()).isEqualTo(ResyncStatus.SUCCEEDED));
        assertThat(count("resync_jobs")).isEqualTo(1);
    }

    @Test
    @DisplayName("a repair that cannot fix everything reports what is still wrong")
    void reports_the_problems_the_source_itself_cannot_resolve() {
        String orderId = "ORD-R4";
        givenReceivedOrderEvent(Payloads.orderEvent("EVT-R4-1", orderId, "ORDER_CREATED", Payloads.T0));
        orderService.aggregateMissing(orderId);
        orderService.events(orderId);
        orderService.eventCount(orderId, 0);
        paymentService.aggregateMissing(orderId);
        paymentService.events(orderId);
        paymentService.eventCount(orderId, 0);

        resync.requestResync(orderId);

        awaitAssertion(() -> assertThat(job(orderId).status()).isEqualTo(ResyncStatus.SUCCEEDED));
        assertThat(job(orderId).remainingDiscrepancies()).isPositive();
        assertThat(issue(orderId).get("status")).isEqualTo("OPEN");
        assertThat(discrepancyTypes(orderId))
                .contains("ORDER_MISSING_IN_SOURCE", "ORDER_EVENT_COUNT_MISMATCH");

        assertThat(localEventIds(orderId)).containsExactly("EVT-R4-1");
    }

    @Test
    @DisplayName("a repair that cannot reach the source fails loudly and says why")
    void fails_when_the_source_is_unreachable() {
        String orderId = "ORD-R5";
        orderService.aggregateUnavailable(orderId);

        resync.requestResync(orderId);

        awaitAssertion(() -> assertThat(job(orderId).status()).isEqualTo(ResyncStatus.FAILED));
        assertThat(job(orderId).failureReason()).contains("order-service");
    }

    @Test
    @DisplayName("many orders are repaired concurrently without interfering with each other")
    void repairs_many_orders_in_parallel() {
        List<String> orderIds = IntStream.rangeClosed(1, 12).mapToObj(i -> "ORD-P" + i).toList();
        orderIds.forEach(orderId -> {
            givenLocallyOnlyTheCreationEvent(orderId);
            givenSourceHasEverything(orderId, "COMPLETED", "150.00");
        });

        orderIds.forEach(resync::requestResync);

        awaitAssertion(() -> assertThat(jdbcTemplate.queryForObject(
                "select count(*) from resync_jobs where status = 'SUCCEEDED'", Long.class))
                .isEqualTo(orderIds.size()));

        orderIds.forEach(orderId -> {
            assertThat(localEventIds(orderId)).hasSize(3);
            assertThat(order(orderId).get("status")).isEqualTo("COMPLETED");
        });
        assertThat(count("resync_jobs")).isEqualTo(orderIds.size());
    }

    @Test
    @DisplayName("a job left behind by a crashed node is picked up again")
    void recovers_a_job_that_was_never_executed() {
        String orderId = "ORD-R6";
        givenSourceHasEverything(orderId, "COMPLETED", "150.00");

        jdbcTemplate.update("""
                insert into resync_jobs (order_id, status, requested_at, attempts)
                values (?, 'PENDING', now() - interval '10 minutes', 0)
                """, orderId);

        assertThat(resync.recoverStaleJobs()).isEqualTo(1);
        awaitAssertion(() -> assertThat(job(orderId).status()).isEqualTo(ResyncStatus.SUCCEEDED));
    }

    @Test
    @DisplayName("a fresh request is left alone by the recovery sweep")
    void does_not_steal_a_job_that_was_only_just_requested() {
        assertThat(resync.recoverStaleJobs()).isZero();
    }

    @Test
    @DisplayName("the newest attempt is what the status endpoint reports")
    void exposes_the_latest_attempt() {
        String orderId = "ORD-R7";
        givenSourceHasEverything(orderId, "COMPLETED", "150.00");

        resync.requestResync(orderId);
        awaitAssertion(() -> assertThat(job(orderId).status()).isEqualTo(ResyncStatus.SUCCEEDED));

        ResyncJob second = resync.requestResync(orderId);
        awaitAssertion(() -> assertThat(job(orderId).status()).isEqualTo(ResyncStatus.SUCCEEDED));

        assertThat(resync.resyncStatus(orderId)).get()
                .extracting(ResyncJob::id).isEqualTo(second.id());
        assertThat(count("resync_jobs")).isEqualTo(2);
    }

    @Test
    @DisplayName("a repair updates the existing issue instead of opening a second one")
    void updates_the_issue_it_could_not_fully_repair() {
        String orderId = "ORD-R8";
        givenReceivedOrderEvent(Payloads.orderEvent("EVT-R8-1", orderId, "ORDER_CREATED", Payloads.T0));
        orderService.modifiedSince(orderId);
        orderService.aggregate(orderId, Payloads.order(orderId, "CANCELLED", "150.00"));
        orderService.events(orderId, Payloads.orderEvent("EVT-R8-1", orderId, "ORDER_CREATED", Payloads.T0));
        orderService.eventCount(orderId, 1);
        paymentService.modifiedSince();
        paymentService.aggregateMissing(orderId);
        paymentService.events(orderId);
        paymentService.eventCount(orderId, 0);

        runAudit.run(AuditTrigger.MANUAL);
        long issueId = jdbcTemplate.queryForObject(
                "select id from audit_issues where order_id = ?", Long.class, orderId);

        resync.requestResync(orderId);
        awaitAssertion(() -> assertThat(job(orderId).status()).isEqualTo(ResyncStatus.SUCCEEDED));

        assertThat(count("audit_issues")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select id from audit_issues where order_id = ?", Long.class, orderId))
                .isEqualTo(issueId);

        assertThat(issue(orderId).get("status")).isEqualTo("OPEN");
        assertThat(discrepancyTypes(orderId))
                .containsExactly("INCOMPLETE_ORDER_HISTORY")
                .doesNotContain("ORDER_STATUS_MISMATCH");
        assertThat(job(orderId).remainingDiscrepancies()).isEqualTo(1);
    }

    private void givenLocallyOnlyTheCreationEvent(String orderId) {
        givenReceivedOrderEvent(Payloads.orderEvent("EVT-" + suffix(orderId) + "-1", orderId,
                "ORDER_CREATED", Payloads.T0));
    }

    private void givenSourceHasEverything(String orderId, String status, String total) {
        String suffix = suffix(orderId);
        orderService.modifiedSince(orderId);
        orderService.aggregate(orderId, Payloads.order(orderId, status, total));
        orderService.events(orderId,
                Payloads.orderEvent("EVT-" + suffix + "-1", orderId, "ORDER_CREATED", Payloads.T0),
                Payloads.orderEvent("EVT-" + suffix + "-2", orderId, "ORDER_PAID", Payloads.T1),
                Payloads.orderEvent("EVT-" + suffix + "-3", orderId, "ORDER_COMPLETED", Payloads.T2));
        orderService.eventCount(orderId, 3);
        paymentService.modifiedSince();
        paymentService.aggregateMissing(orderId);
        paymentService.events(orderId);
        paymentService.eventCount(orderId, 0);
    }

    private String suffix(String orderId) {
        return orderId.substring("ORD-".length());
    }

    private ResyncJob job(String orderId) {
        return resync.resyncStatus(orderId).orElseThrow();
    }

    private Map<String, Object> order(String orderId) {
        return jdbcTemplate.queryForMap("select * from orders where order_id = ?", orderId);
    }

    private Map<String, Object> issue(String orderId) {
        return jdbcTemplate.queryForMap("select * from audit_issues where order_id = ?", orderId);
    }

    private List<String> localEventIds(String orderId) {
        return jdbcTemplate.queryForList(
                "select event_id from ingested_events where order_id = ? and source = 'ORDER'",
                String.class, orderId);
    }

    private List<String> discrepancyTypes(String orderId) {
        return jdbcTemplate.queryForList("""
                select d.type from audit_discrepancies d
                join audit_issues i on i.id = d.issue_id
                where i.order_id = ?
                """, String.class, orderId);
    }
}
