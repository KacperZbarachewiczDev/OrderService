package com.task.ing.orderaudit.integration;

import com.task.ing.orderaudit.application.port.in.RunAuditUseCase;
import com.task.ing.orderaudit.domain.audit.AuditTrigger;
import com.task.ing.orderaudit.support.AbstractIntegrationTest;
import com.task.ing.orderaudit.support.Payloads;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DiscrepancyDetectionIT extends AbstractIntegrationTest {

    private static final String ORDER_ID = "ORD-D1";

    @Autowired
    private RunAuditUseCase runAudit;

    @BeforeEach
    void givenAnOrderThisServiceHoldsAsCompleted() {
        givenReceivedOrderEvent(Payloads.orderEventWithItems("EVT-D1", ORDER_ID, "ORDER_CREATED",
                Payloads.T0, Payloads.item("P-1", 2, "75.00")));
        givenReceivedOrderEvent(Payloads.orderEvent("EVT-D2", ORDER_ID, "ORDER_COMPLETED", Payloads.T2));
        givenReceivedPaymentEvent(Payloads.paymentEvent("PAY-EVT-D1", "PAY-D1", ORDER_ID,
                "PAYMENT_COMPLETED", "PAID", "150.00", Payloads.T1));
    }

    @Test
    @DisplayName("nothing is reported when the source agrees on every field")
    void the_baseline_is_clean() {
        givenSource(matchingOrder(), matchingPayment());

        runAudit();

        assertThat(count("audit_issues")).isZero();
    }

    @Test
    @DisplayName("a status that drifted")
    void detects_a_status_mismatch() {
        givenSource(Payloads.order(ORDER_ID, "CANCELLED", "150.00", Payloads.item("P-1", 2, "75.00")),
                matchingPayment());

        runAudit();

        assertThat(findings()).contains("ORDER_STATUS_MISMATCH");
    }

    @Test
    @DisplayName("a total that drifted")
    void detects_an_amount_mismatch() {
        givenSource(Payloads.order(ORDER_ID, "COMPLETED", "199.99", Payloads.item("P-1", 2, "75.00")),
                matchingPayment());

        runAudit();

        assertThat(findings()).contains("ORDER_AMOUNT_MISMATCH");
    }

    @Test
    @DisplayName("an order settled in another currency")
    void detects_a_currency_mismatch() {
        givenSource("""
                {"orderId": "%s", "customerId": "CUST-1", "status": "COMPLETED",
                 "currency": "EUR", "totalAmount": 150.00, "updatedAt": "%s",
                 "items": [%s]}
                """.formatted(ORDER_ID, Payloads.T2, Payloads.item("P-1", 2, "75.00")),
                matchingPayment());

        runAudit();

        assertThat(findings()).contains("ORDER_CURRENCY_MISMATCH");
    }

    @Test
    @DisplayName("an order attributed to a different customer")
    void detects_a_customer_mismatch() {
        givenSource("""
                {"orderId": "%s", "customerId": "CUST-999", "status": "COMPLETED",
                 "currency": "PLN", "totalAmount": 150.00, "updatedAt": "%s",
                 "items": [%s]}
                """.formatted(ORDER_ID, Payloads.T2, Payloads.item("P-1", 2, "75.00")),
                matchingPayment());

        runAudit();

        assertThat(findings()).contains("ORDER_CUSTOMER_MISMATCH");
    }

    @Test
    @DisplayName("a product this service never recorded")
    void detects_a_missing_product() {
        givenSource(Payloads.order(ORDER_ID, "COMPLETED", "150.00",
                Payloads.item("P-1", 2, "75.00"), Payloads.item("P-2", 1, "20.00")),
                matchingPayment());

        runAudit();

        assertThat(findings()).contains("ORDER_LINE_MISSING");
        assertThat(finding("ORDER_LINE_MISSING").get("field")).isEqualTo("P-2");
    }

    @Test
    @DisplayName("a product this service has and the source does not")
    void detects_an_unexpected_product() {
        givenSource(Payloads.order(ORDER_ID, "COMPLETED", "150.00", Payloads.item("P-9", 1, "150.00")),
                matchingPayment());

        runAudit();

        assertThat(findings()).contains("ORDER_LINE_UNEXPECTED", "ORDER_LINE_MISSING");
    }

    @Test
    @DisplayName("a quantity that drifted")
    void detects_a_quantity_mismatch() {
        givenSource(Payloads.order(ORDER_ID, "COMPLETED", "150.00", Payloads.item("P-1", 5, "75.00")),
                matchingPayment());

        runAudit();

        assertThat(findings()).contains("ORDER_LINE_QUANTITY_MISMATCH");
        assertThat(finding("ORDER_LINE_QUANTITY_MISMATCH").get("expected_value")).isEqualTo("5");
        assertThat(finding("ORDER_LINE_QUANTITY_MISMATCH").get("actual_value")).isEqualTo("2");
    }

    @Test
    @DisplayName("a unit price that drifted")
    void detects_a_price_mismatch() {
        givenSource(Payloads.order(ORDER_ID, "COMPLETED", "150.00", Payloads.item("P-1", 2, "70.00")),
                matchingPayment());

        runAudit();

        assertThat(findings()).contains("ORDER_LINE_PRICE_MISMATCH");
    }

    @Test
    @DisplayName("an order the source no longer knows about")
    void detects_an_order_missing_in_the_source() {
        orderService.modifiedSince(ORDER_ID);
        orderService.aggregateMissing(ORDER_ID);
        orderService.eventCount(ORDER_ID, 2);
        orderService.events(ORDER_ID, sourceOrderEvents());
        givenSourcePayment(matchingPayment());

        runAudit();

        assertThat(findings()).contains("ORDER_MISSING_IN_SOURCE");
    }

    @Test
    @DisplayName("a payment status that drifted")
    void detects_a_payment_status_mismatch() {
        givenSource(matchingOrder(),
                Payloads.payment("PAY-D1", ORDER_ID, "REFUNDED", "150.00"));

        runAudit();

        assertThat(findings()).contains("PAYMENT_STATUS_MISMATCH");
    }

    @Test
    @DisplayName("a payment amount that drifted")
    void detects_a_payment_amount_mismatch() {
        givenSource(matchingOrder(), Payloads.payment("PAY-D1", ORDER_ID, "PAID", "100.00"));

        runAudit();

        assertThat(findings()).contains("PAYMENT_AMOUNT_MISMATCH");
    }

    @Test
    @DisplayName("a payment settled in another currency")
    void detects_a_payment_currency_mismatch() {
        givenSource(matchingOrder(), """
                {"paymentId": "PAY-D1", "orderId": "%s", "status": "PAID",
                 "amount": 150.00, "currency": "EUR", "updatedAt": "%s"}
                """.formatted(ORDER_ID, Payloads.T2));

        runAudit();

        assertThat(findings()).contains("PAYMENT_CURRENCY_MISMATCH");
    }

    @Test
    @DisplayName("a payment recorded under a different identifier")
    void detects_a_payment_identity_mismatch() {
        givenSource(matchingOrder(), Payloads.payment("PAY-OTHER", ORDER_ID, "PAID", "150.00"));

        runAudit();

        assertThat(findings()).contains("PAYMENT_IDENTITY_MISMATCH");
    }

    @Test
    @DisplayName("a payment the source no longer knows about")
    void detects_a_payment_missing_in_the_source() {
        givenSource(matchingOrder(), null);

        runAudit();

        assertThat(findings()).contains("PAYMENT_MISSING_IN_SOURCE");
    }

    @Test
    @DisplayName("more order events upstream than this service ever received")
    void detects_an_order_event_count_mismatch() {
        orderService.modifiedSince(ORDER_ID);
        orderService.aggregate(ORDER_ID, matchingOrder());
        orderService.eventCount(ORDER_ID, 5);
        orderService.events(ORDER_ID, sourceOrderEvents());
        givenSourcePayment(matchingPayment());

        runAudit();

        assertThat(findings()).contains("ORDER_EVENT_COUNT_MISMATCH");
        assertThat(finding("ORDER_EVENT_COUNT_MISMATCH").get("expected_value")).isEqualTo("5");
        assertThat(finding("ORDER_EVENT_COUNT_MISMATCH").get("actual_value")).isEqualTo("2");
    }

    @Test
    @DisplayName("more payment events upstream than this service ever received")
    void detects_a_payment_event_count_mismatch() {
        givenSourceOrder(matchingOrder());
        paymentService.modifiedSince();
        paymentService.aggregate(ORDER_ID, matchingPayment());
        paymentService.eventCount(ORDER_ID, 4);
        paymentService.events(ORDER_ID, sourcePaymentEvents());

        runAudit();

        assertThat(findings()).contains("PAYMENT_EVENT_COUNT_MISMATCH");
    }

    @Test
    @DisplayName("the specific order events that never arrived")
    void names_the_missing_order_events() {
        orderService.modifiedSince(ORDER_ID);
        orderService.aggregate(ORDER_ID, matchingOrder());
        orderService.eventCount(ORDER_ID, 3);
        orderService.events(ORDER_ID,
                Payloads.orderEvent("EVT-D1", ORDER_ID, "ORDER_CREATED", Payloads.T0),
                Payloads.orderEvent("EVT-D-LOST", ORDER_ID, "ORDER_PAID", Payloads.T1),
                Payloads.orderEvent("EVT-D2", ORDER_ID, "ORDER_COMPLETED", Payloads.T2));
        givenSourcePayment(matchingPayment());

        runAudit();

        assertThat(findings()).contains("MISSING_ORDER_EVENTS");
        assertThat(finding("MISSING_ORDER_EVENTS").get("expected_value")).isEqualTo("EVT-D-LOST");
    }

    @Test
    @DisplayName("the specific payment events that never arrived")
    void names_the_missing_payment_events() {
        givenSourceOrder(matchingOrder());
        paymentService.modifiedSince();
        paymentService.aggregate(ORDER_ID, matchingPayment());
        paymentService.eventCount(ORDER_ID, 2);
        paymentService.events(ORDER_ID,
                Payloads.paymentEvent("PAY-EVT-D1", "PAY-D1", ORDER_ID, "PAYMENT_COMPLETED",
                        "PAID", "150.00", Payloads.T1),
                Payloads.paymentEvent("PAY-EVT-LOST", "PAY-D1", ORDER_ID, "PAYMENT_AUTHORIZED",
                        "AUTHORIZED", "150.00", Payloads.T0));

        runAudit();

        assertThat(findings()).contains("MISSING_PAYMENT_EVENTS");
        assertThat(finding("MISSING_PAYMENT_EVENTS").get("expected_value")).isEqualTo("PAY-EVT-LOST");
    }

    @Test
    @DisplayName("several problems on one order are all reported together")
    void reports_every_problem_it_finds() {
        givenSource(Payloads.order(ORDER_ID, "CANCELLED", "199.99", Payloads.item("P-1", 5, "70.00")),
                Payloads.payment("PAY-D1", ORDER_ID, "REFUNDED", "100.00"));

        runAudit();

        assertThat(findings()).contains(
                "ORDER_STATUS_MISMATCH",
                "ORDER_AMOUNT_MISMATCH",
                "ORDER_LINE_QUANTITY_MISMATCH",
                "ORDER_LINE_PRICE_MISMATCH",
                "PAYMENT_STATUS_MISMATCH",
                "PAYMENT_AMOUNT_MISMATCH");
        assertThat(jdbcTemplate.queryForObject(
                "select discrepancy_count from audit_issues where order_id = ?", Integer.class, ORDER_ID))
                .isEqualTo(findings().size());
    }

    private void runAudit() {
        runAudit.run(AuditTrigger.MANUAL).orElseThrow();
    }

    private String matchingOrder() {
        return Payloads.order(ORDER_ID, "COMPLETED", "150.00", Payloads.item("P-1", 2, "75.00"));
    }

    private String matchingPayment() {
        return Payloads.payment("PAY-D1", ORDER_ID, "PAID", "150.00");
    }

    private void givenSource(String orderJson, String paymentJson) {
        givenSourceOrder(orderJson);
        givenSourcePayment(paymentJson);
    }

    private void givenSourceOrder(String orderJson) {
        orderService.modifiedSince(ORDER_ID);
        orderService.aggregate(ORDER_ID, orderJson);
        orderService.eventCount(ORDER_ID, 2);
        orderService.events(ORDER_ID, sourceOrderEvents());
    }

    private void givenSourcePayment(String paymentJson) {
        paymentService.modifiedSince();
        if (paymentJson == null) {
            paymentService.aggregateMissing(ORDER_ID);
        } else {
            paymentService.aggregate(ORDER_ID, paymentJson);
        }
        paymentService.eventCount(ORDER_ID, 1);
        paymentService.events(ORDER_ID, sourcePaymentEvents());
    }

    private String[] sourceOrderEvents() {
        return new String[]{
                Payloads.orderEvent("EVT-D1", ORDER_ID, "ORDER_CREATED", Payloads.T0),
                Payloads.orderEvent("EVT-D2", ORDER_ID, "ORDER_COMPLETED", Payloads.T2)};
    }

    private String[] sourcePaymentEvents() {
        return new String[]{Payloads.paymentEvent("PAY-EVT-D1", "PAY-D1", ORDER_ID,
                "PAYMENT_COMPLETED", "PAID", "150.00", Payloads.T1)};
    }

    private List<String> findings() {
        return jdbcTemplate.queryForList("""
                select d.type from audit_discrepancies d
                join audit_issues i on i.id = d.issue_id
                where i.order_id = ?
                """, String.class, ORDER_ID);
    }

    private Map<String, Object> finding(String type) {
        return jdbcTemplate.queryForMap("""
                select d.* from audit_discrepancies d
                join audit_issues i on i.id = d.issue_id
                where i.order_id = ? and d.type = ?
                """, ORDER_ID, type);
    }
}
