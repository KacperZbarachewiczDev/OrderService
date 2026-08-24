package com.task.ing.orderaudit.integration;

import com.task.ing.orderaudit.application.port.in.ResyncOrderUseCase;
import com.task.ing.orderaudit.application.port.in.RunAuditUseCase;
import com.task.ing.orderaudit.domain.audit.AuditRun;
import com.task.ing.orderaudit.domain.audit.AuditTrigger;
import com.task.ing.orderaudit.domain.resync.ResyncStatus;
import com.task.ing.orderaudit.support.AbstractIntegrationTest;
import com.task.ing.orderaudit.support.Payloads;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class SourceFailureModesIT extends AbstractIntegrationTest {

    private static final String ORDER_ID = "ORD-F1";

    @Autowired
    private RunAuditUseCase runAudit;

    @Autowired
    private ResyncOrderUseCase resync;

    @Test
    @DisplayName("a server error from the order service leaves the order inconclusive")
    void a_failing_order_service_makes_the_order_inconclusive() {
        givenLocalOrder();
        orderService.modifiedSince(ORDER_ID);
        orderService.aggregateUnavailable(ORDER_ID);
        givenNoPayment();

        AuditRun run = runAudit();

        assertThat(run.stats().ordersInconclusive()).isEqualTo(1);
        assertThat(count("audit_issues")).isZero();
    }

    @Test
    @DisplayName("an order service that stops responding leaves the order inconclusive")
    void a_hanging_order_service_makes_the_order_inconclusive() {
        givenLocalOrder();
        orderService.modifiedSince(ORDER_ID);
        ORDER_SERVICE.stubFor(get(urlPathEqualTo("/orders/" + ORDER_ID))
                .willReturn(aResponse().withStatus(200).withFixedDelay(3_000)
                        .withHeader("Content-Type", "application/json")
                        .withBody(Payloads.order(ORDER_ID, "COMPLETED", "150.00"))));
        givenNoPayment();

        assertThat(runAudit().stats().ordersInconclusive()).isEqualTo(1);
    }

    @Test
    @DisplayName("a failing payment service leaves the order inconclusive")
    void a_failing_payment_service_makes_the_order_inconclusive() {
        givenLocalOrder();
        givenHealthyOrderSource();
        paymentService.modifiedSince();
        paymentService.aggregateUnavailable(ORDER_ID);

        assertThat(runAudit().stats().ordersInconclusive()).isEqualTo(1);
        assertThat(count("audit_issues")).isZero();
    }

    @Test
    @DisplayName("a failing event count endpoint leaves the order inconclusive")
    void a_failing_count_endpoint_makes_the_order_inconclusive() {
        givenLocalOrder();
        orderService.modifiedSince(ORDER_ID);
        orderService.aggregate(ORDER_ID, Payloads.order(ORDER_ID, "COMPLETED", "150.00"));
        ORDER_SERVICE.stubFor(get(urlPathEqualTo("/orders/" + ORDER_ID + "/events/count"))
                .willReturn(aResponse().withStatus(503)));
        givenNoPayment();

        assertThat(runAudit().stats().ordersInconclusive()).isEqualTo(1);
    }

    @Test
    @DisplayName("a failing history endpoint leaves the order inconclusive rather than half-checked")
    void a_failing_history_endpoint_makes_the_order_inconclusive() {
        givenLocalOrder();
        orderService.modifiedSince(ORDER_ID);
        orderService.aggregate(ORDER_ID, Payloads.order(ORDER_ID, "COMPLETED", "150.00"));

        orderService.eventCount(ORDER_ID, 9);
        orderService.eventsUnavailable(ORDER_ID);
        givenNoPayment();

        AuditRun run = runAudit();

        assertThat(run.stats().ordersInconclusive()).isEqualTo(1);
        assertThat(count("audit_issues")).isZero();
    }

    @Test
    @DisplayName("an unreadable response is treated as an outage, not as an empty order")
    void unreadable_json_makes_the_order_inconclusive() {
        givenLocalOrder();
        orderService.modifiedSince(ORDER_ID);
        ORDER_SERVICE.stubFor(get(urlPathEqualTo("/orders/" + ORDER_ID))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{ not json at all")));
        givenNoPayment();

        assertThat(runAudit().stats().ordersInconclusive()).isEqualTo(1);
    }

    @Test
    @DisplayName("an open issue survives a run that could not verify it")
    void does_not_close_an_open_issue_it_could_not_verify() {
        givenLocalOrder();
        orderService.modifiedSince(ORDER_ID);
        orderService.aggregate(ORDER_ID, Payloads.order(ORDER_ID, "CANCELLED", "150.00"));
        orderService.eventCount(ORDER_ID, 2);
        orderService.events(ORDER_ID, sourceEvents());
        givenNoPayment();
        runAudit();
        assertThat(issueStatus()).isEqualTo("OPEN");

        orderService.aggregateUnavailable(ORDER_ID);
        AuditRun second = runAudit();

        assertThat(second.stats().ordersInconclusive()).isEqualTo(1);
        assertThat(issueStatus()).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("the run carries on when one change feed cannot be listed")
    void one_broken_change_feed_does_not_stop_the_run() {
        givenLocalOrder();
        orderService.modifiedSinceUnavailable();
        orderService.aggregate(ORDER_ID, Payloads.order(ORDER_ID, "CANCELLED", "150.00"));
        orderService.eventCount(ORDER_ID, 2);
        orderService.events(ORDER_ID, sourceEvents());
        paymentService.modifiedSince(ORDER_ID);
        paymentService.aggregateMissing(ORDER_ID);
        paymentService.eventCount(ORDER_ID, 0);
        paymentService.events(ORDER_ID);

        AuditRun run = runAudit();

        assertThat(run.stats().ordersWithIssues()).isEqualTo(1);
    }

    @Test
    @DisplayName("with both change feeds down the run still re-checks the known problems")
    void both_feeds_down_still_re_checks_open_issues() {
        givenLocalOrder();
        orderService.modifiedSince(ORDER_ID);
        orderService.aggregate(ORDER_ID, Payloads.order(ORDER_ID, "CANCELLED", "150.00"));
        orderService.eventCount(ORDER_ID, 2);
        orderService.events(ORDER_ID, sourceEvents());
        givenNoPayment();
        runAudit();

        orderService.modifiedSinceUnavailable();
        paymentService.modifiedSinceUnavailable();
        AuditRun second = runAudit();

        assertThat(second.stats().ordersWithIssues()).isEqualTo(1);
        assertThat(issueStatus()).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("an order neither system knows about is simply clean")
    void an_order_nobody_knows_is_clean() {
        orderService.modifiedSince(ORDER_ID);
        orderService.aggregateMissing(ORDER_ID);
        orderService.eventCount(ORDER_ID, 0);
        orderService.events(ORDER_ID);
        givenNoPayment();

        AuditRun run = runAudit();

        assertThat(run.stats().ordersChecked()).isEqualTo(1);
        assertThat(run.stats().ordersWithIssues()).isZero();
    }

    @Test
    @DisplayName("a problem is found as soon as the source comes back")
    void detects_the_problem_once_the_source_recovers() {
        givenLocalOrder();
        orderService.modifiedSince(ORDER_ID);
        orderService.aggregateUnavailable(ORDER_ID);
        givenNoPayment();
        assertThat(runAudit().stats().ordersInconclusive()).isEqualTo(1);

        orderService.aggregate(ORDER_ID, Payloads.order(ORDER_ID, "CANCELLED", "150.00"));
        orderService.eventCount(ORDER_ID, 2);
        orderService.events(ORDER_ID, sourceEvents());

        assertThat(runAudit().stats().ordersWithIssues()).isEqualTo(1);
        assertThat(issueStatus()).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("a repair fails loudly when the payment system is unreachable")
    void a_repair_fails_when_the_payment_service_is_down() {
        givenLocalOrder();
        orderService.aggregate(ORDER_ID, Payloads.order(ORDER_ID, "COMPLETED", "150.00"));
        orderService.events(ORDER_ID, sourceEvents());
        orderService.eventCount(ORDER_ID, 2);
        paymentService.aggregateUnavailable(ORDER_ID);

        resync.requestResync(ORDER_ID);

        awaitAssertion(() -> assertThat(resync.resyncStatus(ORDER_ID).orElseThrow().status())
                .isEqualTo(ResyncStatus.FAILED));
        assertThat(resync.resyncStatus(ORDER_ID).orElseThrow().failureReason())
                .contains("payment-service");
    }

    @Test
    @DisplayName("a repair fails loudly when the history cannot be downloaded")
    void a_repair_fails_when_the_history_is_unavailable() {
        givenLocalOrder();
        orderService.aggregate(ORDER_ID, Payloads.order(ORDER_ID, "COMPLETED", "150.00"));
        orderService.eventsUnavailable(ORDER_ID);
        orderService.eventCount(ORDER_ID, 2);

        resync.requestResync(ORDER_ID);

        awaitAssertion(() -> assertThat(resync.resyncStatus(ORDER_ID).orElseThrow().status())
                .isEqualTo(ResyncStatus.FAILED));
    }

    @Test
    @DisplayName("a failed repair leaves the local data untouched")
    void a_failed_repair_changes_nothing() {
        givenLocalOrder();
        orderService.aggregateUnavailable(ORDER_ID);

        resync.requestResync(ORDER_ID);
        awaitAssertion(() -> assertThat(resync.resyncStatus(ORDER_ID).orElseThrow().status())
                .isEqualTo(ResyncStatus.FAILED));

        assertThat(count("ingested_events")).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "select status from orders where order_id = ?", String.class, ORDER_ID))
                .isEqualTo("COMPLETED");
    }

    private AuditRun runAudit() {
        return runAudit.run(AuditTrigger.MANUAL).orElseThrow();
    }

    private void givenLocalOrder() {
        givenReceivedOrderEvent(Payloads.orderEvent("EVT-F1-1", ORDER_ID, "ORDER_CREATED", Payloads.T0));
        givenReceivedOrderEvent(Payloads.orderEvent("EVT-F1-2", ORDER_ID, "ORDER_COMPLETED", Payloads.T2));
    }

    private void givenHealthyOrderSource() {
        orderService.modifiedSince(ORDER_ID);
        orderService.aggregate(ORDER_ID, Payloads.order(ORDER_ID, "COMPLETED", "150.00"));
        orderService.eventCount(ORDER_ID, 2);
        orderService.events(ORDER_ID, sourceEvents());
    }

    private void givenNoPayment() {
        paymentService.modifiedSince();
        paymentService.aggregateMissing(ORDER_ID);
        paymentService.eventCount(ORDER_ID, 0);
        paymentService.events(ORDER_ID);
    }

    private String[] sourceEvents() {
        return new String[]{
                Payloads.orderEvent("EVT-F1-1", ORDER_ID, "ORDER_CREATED", Payloads.T0),
                Payloads.orderEvent("EVT-F1-2", ORDER_ID, "ORDER_COMPLETED", Payloads.T2)};
    }

    private String issueStatus() {
        return jdbcTemplate.queryForObject(
                "select status from audit_issues where order_id = ?", String.class, ORDER_ID);
    }
}
