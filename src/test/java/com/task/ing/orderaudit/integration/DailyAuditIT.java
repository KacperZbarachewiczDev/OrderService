package com.task.ing.orderaudit.integration;

import com.task.ing.orderaudit.application.port.in.RunAuditUseCase;
import com.task.ing.orderaudit.domain.audit.AuditRun;
import com.task.ing.orderaudit.domain.audit.AuditTrigger;
import com.task.ing.orderaudit.support.AbstractIntegrationTest;
import com.task.ing.orderaudit.support.Payloads;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DailyAuditIT extends AbstractIntegrationTest {

    @Autowired
    private RunAuditUseCase runAudit;

    @Test
    @DisplayName("an order both systems agree on produces no issue and no e-mail")
    void a_consistent_order_raises_nothing() {
        String orderId = "ORD-A1";
        givenLocallyCompleted(orderId);
        givenSourceCompleted(orderId, "150.00");

        AuditRun run = runNow();

        assertThat(run.stats().ordersChecked()).isEqualTo(1);
        assertThat(run.stats().ordersWithIssues()).isZero();
        assertThat(count("audit_issues")).isZero();
        assertThat(count("notification_outbox")).isZero();
    }

    @Test
    @DisplayName("an order the source knows and this service never received is reported as critical")
    void reports_an_order_that_never_arrived() {
        String orderId = "ORD-A2";
        orderService.modifiedSince(orderId);
        orderService.healthyOrder(orderId, "COMPLETED", "150.00",
                Payloads.orderEvent("EVT-A2-1", orderId, "ORDER_CREATED", Payloads.T0),
                Payloads.orderEvent("EVT-A2-2", orderId, "ORDER_COMPLETED", Payloads.T2));
        paymentService.modifiedSince();
        paymentService.aggregateMissing(orderId);
        paymentService.eventCount(orderId, 0);
        paymentService.events(orderId);

        runNow();

        Map<String, Object> issue = issue(orderId);
        assertThat(issue.get("status")).isEqualTo("OPEN");
        assertThat(issue.get("highest_severity")).isEqualTo("CRITICAL");
        assertThat(discrepancyTypes(orderId)).contains("ORDER_MISSING_LOCALLY");
    }

    @Test
    @DisplayName("a status and a total that drifted are both reported, against the same order")
    void reports_business_field_drift() {
        String orderId = "ORD-A3";
        givenLocallyCompleted(orderId);
        orderService.modifiedSince(orderId);
        orderService.aggregate(orderId, Payloads.order(orderId, "CANCELLED", "999.00"));
        orderService.events(orderId,
                Payloads.orderEvent("EVT-A3-1", orderId, "ORDER_CREATED", Payloads.T0),
                Payloads.orderEvent("EVT-A3-2", orderId, "ORDER_COMPLETED", Payloads.T2));
        orderService.eventCount(orderId, 2);
        givenNoPayment(orderId);

        runNow();

        assertThat(discrepancyTypes(orderId))
                .contains("ORDER_STATUS_MISMATCH", "ORDER_AMOUNT_MISMATCH");
        assertThat(issue(orderId).get("highest_severity")).isEqualTo("CRITICAL");
    }

    @Test
    @DisplayName("a payment the source reports and this service never saw is reported")
    void reports_a_missing_payment() {
        String orderId = "ORD-A4";
        givenLocallyCompleted(orderId);
        givenSourceCompleted(orderId, "150.00");
        paymentService.aggregate(orderId, Payloads.payment("PAY-A4", orderId, "PAID", "150.00"));
        paymentService.eventCount(orderId, 1);
        paymentService.events(orderId,
                Payloads.paymentEvent("PAY-EVT-A4", "PAY-A4", orderId, "PAYMENT_COMPLETED",
                        "PAID", "150.00", Payloads.T1));

        runNow();

        assertThat(discrepancyTypes(orderId))
                .contains("PAYMENT_MISSING_LOCALLY", "PAYMENT_EVENT_COUNT_MISMATCH", "MISSING_PAYMENT_EVENTS");
    }

    @Test
    @DisplayName("events the stream dropped are named individually, not just counted")
    void names_the_events_that_never_arrived() {
        String orderId = "ORD-A5";
        givenReceivedOrderEvent(Payloads.orderEvent("EVT-A5-1", orderId, "ORDER_CREATED", Payloads.T0));
        givenReceivedOrderEvent(Payloads.orderEvent("EVT-A5-3", orderId, "ORDER_COMPLETED", Payloads.T2));

        orderService.modifiedSince(orderId);
        orderService.aggregate(orderId, Payloads.order(orderId, "COMPLETED", "150.00"));
        orderService.eventCount(orderId, 3);
        orderService.events(orderId,
                Payloads.orderEvent("EVT-A5-1", orderId, "ORDER_CREATED", Payloads.T0),
                Payloads.orderEvent("EVT-A5-2", orderId, "ORDER_PAID", Payloads.T1),
                Payloads.orderEvent("EVT-A5-3", orderId, "ORDER_COMPLETED", Payloads.T2));
        givenNoPayment(orderId);

        runNow();

        assertThat(discrepancyTypes(orderId))
                .contains("ORDER_EVENT_COUNT_MISMATCH", "MISSING_ORDER_EVENTS");
        assertThat(discrepancies(orderId))
                .filteredOn(row -> row.get("type").equals("MISSING_ORDER_EVENTS"))
                .singleElement()
                .satisfies(row -> assertThat(row.get("expected_value")).isEqualTo("EVT-A5-2"));
    }

    @Test
    @DisplayName("a completed order whose creation event never arrived is reported as incomplete history")
    void reports_an_incomplete_history_even_when_the_counts_match() {
        String orderId = "ORD-A6";
        givenReceivedOrderEvent(Payloads.orderEvent("EVT-A6-X", orderId, "ORDER_UPDATED", Payloads.T0));
        givenReceivedOrderEvent(Payloads.orderEvent("EVT-A6-2", orderId, "ORDER_COMPLETED", Payloads.T2));

        orderService.modifiedSince(orderId);
        orderService.aggregate(orderId, Payloads.order(orderId, "COMPLETED", "150.00"));
        orderService.eventCount(orderId, 2);
        orderService.events(orderId,
                Payloads.orderEvent("EVT-A6-X", orderId, "ORDER_UPDATED", Payloads.T0),
                Payloads.orderEvent("EVT-A6-2", orderId, "ORDER_COMPLETED", Payloads.T2));
        givenNoPayment(orderId);

        runNow();

        assertThat(discrepancyTypes(orderId))
                .contains("INCOMPLETE_ORDER_HISTORY")
                .doesNotContain("ORDER_EVENT_COUNT_MISMATCH");
        assertThat(discrepancies(orderId))
                .filteredOn(row -> row.get("type").equals("INCOMPLETE_ORDER_HISTORY"))
                .singleElement()
                .satisfies(row -> assertThat(row.get("field")).isEqualTo("ORDER_CREATED"));
    }

    @Test
    @DisplayName("an order the source could not be asked about is left inconclusive, not flagged")
    void leaves_an_order_inconclusive_when_the_source_is_down() {
        String orderId = "ORD-A7";
        givenLocallyCompleted(orderId);
        orderService.modifiedSince(orderId);
        orderService.aggregateUnavailable(orderId);
        paymentService.modifiedSince();
        paymentService.aggregateMissing(orderId);
        paymentService.eventCount(orderId, 0);

        AuditRun run = runNow();

        assertThat(run.stats().ordersInconclusive()).isEqualTo(1);
        assertThat(run.stats().ordersWithIssues()).isZero();
        assertThat(run.stats().ordersChecked()).isZero();
        assertThat(count("audit_issues")).isZero();
        assertThat(count("notification_outbox")).isZero();
    }

    @Test
    @DisplayName("an issue disappears from the list once the data really matches again")
    void closes_an_issue_when_the_order_becomes_consistent() {
        String orderId = "ORD-A8";
        givenLocallyCompleted(orderId);
        orderService.modifiedSince(orderId);
        orderService.aggregate(orderId, Payloads.order(orderId, "CANCELLED", "150.00"));
        orderService.events(orderId,
                Payloads.orderEvent("EVT-A8-1", orderId, "ORDER_CREATED", Payloads.T0),
                Payloads.orderEvent("EVT-A8-2", orderId, "ORDER_COMPLETED", Payloads.T2));
        orderService.eventCount(orderId, 2);
        givenNoPayment(orderId);

        runNow();
        assertThat(issue(orderId).get("status")).isEqualTo("OPEN");

        orderService.aggregate(orderId, Payloads.order(orderId, "COMPLETED", "150.00"));
        AuditRun second = runNow();

        assertThat(issue(orderId).get("status")).isEqualTo("RESOLVED");
        assertThat(issue(orderId).get("discrepancy_count")).isEqualTo(0);
        assertThat(discrepancies(orderId)).isEmpty();
        assertThat(second.stats().ordersResolved()).isEqualTo(1);
    }

    @Test
    @DisplayName("a run that finds problems queues one e-mail naming them")
    void queues_a_notification_describing_the_findings() {
        String orderId = "ORD-A9";
        givenLocallyCompleted(orderId);
        orderService.modifiedSince(orderId);
        orderService.aggregate(orderId, Payloads.order(orderId, "CANCELLED", "150.00"));
        orderService.events(orderId,
                Payloads.orderEvent("EVT-A9-1", orderId, "ORDER_CREATED", Payloads.T0),
                Payloads.orderEvent("EVT-A9-2", orderId, "ORDER_COMPLETED", Payloads.T2));
        orderService.eventCount(orderId, 2);
        givenNoPayment(orderId);

        AuditRun run = runNow();

        Map<String, Object> message = jdbcTemplate.queryForMap("select * from notification_outbox");
        assertThat(message.get("status")).isEqualTo("PENDING");
        assertThat(message.get("recipients")).isEqualTo("ops@example.com");
        assertThat((String) message.get("subject")).contains("1 order with issues");
        assertThat((String) message.get("body"))
                .contains(orderId)
                .contains("ORDER_STATUS_MISMATCH");
        assertThat(message.get("audit_run_id")).isEqualTo(run.id());
    }

    @Test
    @DisplayName("the run is recorded with its window and counters")
    void records_the_run_itself() {
        givenSourceListsNothing();

        AuditRun run = runNow();

        Map<String, Object> stored = jdbcTemplate.queryForMap("select * from audit_runs where id = ?", run.id());
        assertThat(stored.get("status")).isEqualTo("COMPLETED");
        assertThat(stored.get("trigger_type")).isEqualTo("MANUAL");
        assertThat(stored.get("window_from")).isNotNull();
        assertThat(stored.get("finished_at")).isNotNull();
    }

    @Test
    @DisplayName("the next run starts where the last successful one stopped")
    void advances_the_audit_window() {
        givenSourceListsNothing();

        AuditRun first = runNow();
        AuditRun second = runNow();

        assertThat(second.windowFrom()).isEqualTo(first.windowTo());
    }

    @Test
    @DisplayName("event histories are downloaded only for the orders whose counts disagree")
    void does_not_download_histories_it_does_not_need() {
        String orderId = "ORD-A10";
        givenLocallyCompleted(orderId);
        givenSourceCompleted(orderId, "150.00");

        runNow();

        assertThat(orderService.requestsTo("/orders/" + orderId + "/events/count")).isEqualTo(1);
        assertThat(orderService.requestsTo("/orders/" + orderId + "/events")).isZero();
    }

    @Test
    @DisplayName("an order changed only in the payment system is still audited")
    void audits_orders_the_payment_system_reports_as_changed() {
        String orderId = "ORD-A11";
        givenLocallyCompleted(orderId);
        orderService.modifiedSince();
        orderService.aggregate(orderId, Payloads.order(orderId, "COMPLETED", "150.00"));
        orderService.eventCount(orderId, 2);
        orderService.events(orderId,
                Payloads.orderEvent("EVT-A11-1", orderId, "ORDER_CREATED", Payloads.T0),
                Payloads.orderEvent("EVT-A11-2", orderId, "ORDER_COMPLETED", Payloads.T2));

        paymentService.modifiedSince(orderId);
        paymentService.aggregate(orderId, Payloads.payment("PAY-A11", orderId, "PAID", "150.00"));
        paymentService.eventCount(orderId, 1);
        paymentService.events(orderId,
                Payloads.paymentEvent("PAY-EVT-A11", "PAY-A11", orderId, "PAYMENT_COMPLETED",
                        "PAID", "150.00", Payloads.T1));

        AuditRun run = runNow();

        assertThat(run.stats().ordersWithIssues()).isEqualTo(1);
        assertThat(discrepancyTypes(orderId)).contains("PAYMENT_MISSING_LOCALLY");
    }

    @Test
    @DisplayName("the list of changed orders is followed across pages")
    void follows_the_paging_of_the_modified_orders_endpoint() {
        orderService.modifiedSinceFirstPage("page-2", "ORD-A12");
        orderService.modifiedSinceNextPage("page-2", "ORD-A13");
        for (String orderId : List.of("ORD-A12", "ORD-A13")) {
            orderService.aggregateMissing(orderId);
            orderService.eventCount(orderId, 0);
            orderService.events(orderId);
            givenNoPayment(orderId);
        }
        paymentService.modifiedSince();

        AuditRun run = runNow();

        assertThat(run.stats().ordersChecked()).isEqualTo(2);
    }

    @Test
    @DisplayName("several orders are audited in one pass and reported together")
    void audits_a_batch_of_orders_in_one_run() {
        List<String> orderIds = List.of("ORD-B1", "ORD-B2", "ORD-B3", "ORD-B4");
        orderIds.forEach(this::givenLocallyCompleted);

        orderService.modifiedSince(orderIds.toArray(String[]::new));
        orderIds.forEach(orderId -> {
            orderService.eventCount(orderId, 2);
            orderService.events(orderId,
                    Payloads.orderEvent("EVT-" + orderId + "-1", orderId, "ORDER_CREATED", Payloads.T0),
                    Payloads.orderEvent("EVT-" + orderId + "-2", orderId, "ORDER_COMPLETED", Payloads.T2));
            paymentService.aggregateMissing(orderId);
            paymentService.eventCount(orderId, 0);
            paymentService.events(orderId);
        });
        paymentService.modifiedSince();

        orderService.aggregate("ORD-B1", Payloads.order("ORD-B1", "COMPLETED", "150.00"));
        orderService.aggregate("ORD-B2", Payloads.order("ORD-B2", "CANCELLED", "150.00"));
        orderService.aggregate("ORD-B3", Payloads.order("ORD-B3", "COMPLETED", "999.00"));
        orderService.aggregate("ORD-B4", Payloads.order("ORD-B4", "COMPLETED", "150.00"));

        AuditRun run = runNow();

        assertThat(run.stats().ordersChecked()).isEqualTo(4);
        assertThat(run.stats().ordersWithIssues()).isEqualTo(2);
        assertThat(count("audit_issues")).isEqualTo(2);
        assertThat(discrepancyTypes("ORD-B2")).contains("ORDER_STATUS_MISMATCH");
        assertThat(discrepancyTypes("ORD-B3")).contains("ORDER_AMOUNT_MISMATCH");
    }

    @Test
    @DisplayName("a quantity that drifted on one product is reported against that product")
    void reports_item_level_differences() {
        String orderId = "ORD-A14";
        givenReceivedOrderEvent(Payloads.orderEventWithItems("EVT-" + orderId + "-1", orderId,
                "ORDER_CREATED", Payloads.T0, Payloads.item("P-1", 2, "75.00")));
        givenReceivedOrderEvent(Payloads.orderEvent("EVT-" + orderId + "-2", orderId,
                "ORDER_COMPLETED", Payloads.T2));

        orderService.modifiedSince(orderId);
        orderService.aggregate(orderId, Payloads.order(orderId, "COMPLETED", "150.00",
                Payloads.item("P-1", 4, "75.00")));
        orderService.eventCount(orderId, 2);
        orderService.events(orderId,
                Payloads.orderEvent("EVT-" + orderId + "-1", orderId, "ORDER_CREATED", Payloads.T0),
                Payloads.orderEvent("EVT-" + orderId + "-2", orderId, "ORDER_COMPLETED", Payloads.T2));
        givenNoPayment(orderId);

        runNow();

        assertThat(discrepancies(orderId))
                .filteredOn(row -> row.get("type").equals("ORDER_LINE_QUANTITY_MISMATCH"))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.get("field")).isEqualTo("P-1");
                    assertThat(row.get("expected_value")).isEqualTo("4");
                    assertThat(row.get("actual_value")).isEqualTo("2");
                });
    }

    @Test
    @DisplayName("a payment whose order never reached this service is reported on both counts")
    void reports_an_order_known_only_through_its_payment() {
        String orderId = "ORD-A15";
        givenReceivedPaymentEvent(Payloads.paymentEvent("PAY-EVT-A15", "PAY-A15", orderId,
                "PAYMENT_COMPLETED", "PAID", "150.00", Payloads.T1));

        orderService.modifiedSince(orderId);
        orderService.aggregate(orderId, Payloads.order(orderId, "COMPLETED", "150.00"));
        orderService.eventCount(orderId, 2);
        orderService.events(orderId,
                Payloads.orderEvent("EVT-A15-1", orderId, "ORDER_CREATED", Payloads.T0),
                Payloads.orderEvent("EVT-A15-2", orderId, "ORDER_COMPLETED", Payloads.T2));
        paymentService.modifiedSince(orderId);
        paymentService.aggregate(orderId, Payloads.payment("PAY-A15", orderId, "PAID", "150.00"));
        paymentService.eventCount(orderId, 1);
        paymentService.events(orderId,
                Payloads.paymentEvent("PAY-EVT-A15", "PAY-A15", orderId, "PAYMENT_COMPLETED",
                        "PAID", "150.00", Payloads.T1));

        runNow();

        assertThat(discrepancyTypes(orderId))
                .contains("ORDER_MISSING_LOCALLY", "ORDER_EVENT_COUNT_MISMATCH");
        assertThat(issue(orderId).get("highest_severity")).isEqualTo("CRITICAL");
    }

    private AuditRun runNow() {
        return runAudit.run(AuditTrigger.MANUAL).orElseThrow();
    }

    private void givenLocallyCompleted(String orderId) {
        givenReceivedOrderEvent(Payloads.orderEvent("EVT-" + orderId + "-1", orderId, "ORDER_CREATED", Payloads.T0));
        givenReceivedOrderEvent(Payloads.orderEvent("EVT-" + orderId + "-2", orderId, "ORDER_COMPLETED", Payloads.T2));
    }

    private void givenSourceCompleted(String orderId, String total) {
        orderService.modifiedSince(orderId);
        orderService.aggregate(orderId, Payloads.order(orderId, "COMPLETED", total));
        orderService.eventCount(orderId, 2);
        orderService.events(orderId,
                Payloads.orderEvent("EVT-" + orderId + "-1", orderId, "ORDER_CREATED", Payloads.T0),
                Payloads.orderEvent("EVT-" + orderId + "-2", orderId, "ORDER_COMPLETED", Payloads.T2));
        givenNoPayment(orderId);
    }

    private void givenNoPayment(String orderId) {
        paymentService.modifiedSince();
        paymentService.aggregateMissing(orderId);
        paymentService.eventCount(orderId, 0);
        paymentService.events(orderId);
    }

    private void givenSourceListsNothing() {
        orderService.modifiedSince();
        paymentService.modifiedSince();
    }

    private Map<String, Object> issue(String orderId) {
        return jdbcTemplate.queryForMap("select * from audit_issues where order_id = ?", orderId);
    }

    private List<Map<String, Object>> discrepancies(String orderId) {
        return jdbcTemplate.queryForList("""
                select d.* from audit_discrepancies d
                join audit_issues i on i.id = d.issue_id
                where i.order_id = ?
                """, orderId);
    }

    private List<String> discrepancyTypes(String orderId) {
        return discrepancies(orderId).stream().map(row -> (String) row.get("type")).toList();
    }
}
