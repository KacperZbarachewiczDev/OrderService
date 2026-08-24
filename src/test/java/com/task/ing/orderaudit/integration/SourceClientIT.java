package com.task.ing.orderaudit.integration;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.task.ing.orderaudit.application.port.out.OrderSourceClient;
import com.task.ing.orderaudit.application.port.out.PaymentSourceClient;
import com.task.ing.orderaudit.application.port.out.SourceEvent;
import com.task.ing.orderaudit.application.port.out.SourceUnavailableException;
import com.task.ing.orderaudit.domain.model.EventRef;
import com.task.ing.orderaudit.domain.model.Money;
import com.task.ing.orderaudit.domain.model.OrderLine;
import com.task.ing.orderaudit.domain.model.OrderSnapshot;
import com.task.ing.orderaudit.domain.model.OrderStatus;
import com.task.ing.orderaudit.domain.model.PaymentSnapshot;
import com.task.ing.orderaudit.domain.model.PaymentStatus;
import com.task.ing.orderaudit.support.AbstractIntegrationTest;
import com.task.ing.orderaudit.support.Payloads;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourceClientIT extends AbstractIntegrationTest {

    private static final String ORDER_ID = "ORD-S1";

    @Autowired
    private OrderSourceClient orderClient;

    @Autowired
    private PaymentSourceClient paymentClient;

    @Test
    @DisplayName("an order is read with its items and its total")
    void reads_an_order() {
        orderService.aggregate(ORDER_ID, Payloads.order(ORDER_ID, "COMPLETED", "150.00",
                Payloads.item("P-1", 2, "75.00")));

        OrderSnapshot order = orderClient.fetchOrder(ORDER_ID).orElseThrow();

        assertThat(order.orderId()).isEqualTo(ORDER_ID);
        assertThat(order.customerId()).isEqualTo("CUST-1");
        assertThat(order.status()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(order.totalAmount()).isEqualTo(Money.of("150.00", "PLN"));
        assertThat(order.lines()).singleElement()
                .isEqualTo(new OrderLine("P-1", 2, Money.of("75.00", "PLN")));
    }

    @Test
    @DisplayName("a status the audit has never heard of is carried through rather than crashing ingestion")
    void tolerates_an_unknown_status() {
        orderService.aggregate(ORDER_ID, Payloads.order(ORDER_ID, "AWAITING_CUSTOMS", "150.00"));

        assertThat(orderClient.fetchOrder(ORDER_ID).orElseThrow().status())
                .isEqualTo(OrderStatus.UNKNOWN);
    }

    @Test
    @DisplayName("an order the source does not have comes back as absent, not as a failure")
    void reports_a_missing_order_as_absent() {
        orderService.aggregateMissing(ORDER_ID);

        assertThat(orderClient.fetchOrder(ORDER_ID)).isEmpty();
    }

    @Test
    @DisplayName("a not-found answered with an HTML page is still read as absent")
    void reads_an_html_not_found_as_absent() {
        ORDER_SERVICE.stubFor(get(urlPathEqualTo("/orders/" + ORDER_ID))
                .willReturn(aResponse().withStatus(404)
                        .withHeader("Content-Type", "text/html")
                        .withBody("<html><body><h1>404 Not Found</h1></body></html>")));

        assertThat(orderClient.fetchOrder(ORDER_ID)).isEmpty();
    }

    @Test
    @DisplayName("a server error answered with an HTML page is still read as an outage")
    void reads_an_html_server_error_as_an_outage() {
        ORDER_SERVICE.stubFor(get(urlPathEqualTo("/orders/" + ORDER_ID))
                .willReturn(aResponse().withStatus(500)
                        .withHeader("Content-Type", "text/html")
                        .withBody("<html><body><h1>500</h1></body></html>")));

        assertThatThrownBy(() -> orderClient.fetchOrder(ORDER_ID))
                .isInstanceOf(SourceUnavailableException.class);
    }

    @Test
    @DisplayName("an empty count response for an unknown order is read as no events")
    void reads_an_html_not_found_count_as_zero() {
        ORDER_SERVICE.stubFor(get(urlPathEqualTo("/orders/" + ORDER_ID + "/events/count"))
                .willReturn(aResponse().withStatus(404)
                        .withHeader("Content-Type", "text/html")
                        .withBody("<html>nope</html>")));

        assertThat(orderClient.countEvents(ORDER_ID)).isZero();
    }

    @Test
    @DisplayName("a source that keeps failing raises an error instead of reporting the order as gone")
    void refuses_to_treat_an_outage_as_data() {
        orderService.aggregateUnavailable(ORDER_ID);

        assertThatThrownBy(() -> orderClient.fetchOrder(ORDER_ID))
                .isInstanceOf(SourceUnavailableException.class)
                .hasMessageContaining("order-service");

        assertThat(orderService.requestsTo("/orders/" + ORDER_ID)).isEqualTo(2);
    }

    @Test
    @DisplayName("a hiccup is retried and the second attempt is used")
    void retries_a_transient_failure() {
        ORDER_SERVICE.stubFor(get(urlPathEqualTo("/orders/" + ORDER_ID))
                .inScenario("flaky").whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("recovered"));
        ORDER_SERVICE.stubFor(get(urlPathEqualTo("/orders/" + ORDER_ID))
                .inScenario("flaky").whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(Payloads.order(ORDER_ID, "COMPLETED", "150.00"))));

        assertThat(orderClient.fetchOrder(ORDER_ID)).isPresent();
        assertThat(orderService.requestsTo("/orders/" + ORDER_ID)).isEqualTo(2);
    }

    @Test
    @DisplayName("a source that accepts the connection and then goes quiet is treated as unavailable")
    void treats_a_timeout_as_unavailable() {
        ORDER_SERVICE.stubFor(get(urlPathEqualTo("/orders/" + ORDER_ID))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withFixedDelay(3_000)
                        .withBody(Payloads.order(ORDER_ID, "COMPLETED", "150.00"))));

        assertThatThrownBy(() -> orderClient.fetchOrder(ORDER_ID))
                .isInstanceOf(SourceUnavailableException.class);
    }

    @Test
    @DisplayName("an event history is read with its identity, ordering and original payload")
    void reads_an_event_history() {
        ORDER_SERVICE.stubFor(get(urlPathEqualTo("/orders/" + ORDER_ID + "/events"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                [
                                  {"eventId": "EVT-1", "orderId": "%s", "eventType": "ORDER_CREATED",
                                   "occurredAt": "2026-08-19T10:00:00Z", "sequenceNo": 1,
                                   "somethingWeDoNotModelYet": "keep me"}
                                ]
                                """.formatted(ORDER_ID))));

        List<SourceEvent> events = orderClient.fetchEvents(ORDER_ID);

        assertThat(events).hasSize(1);
        EventRef ref = events.getFirst().ref();
        assertThat(ref.eventId()).isEqualTo("EVT-1");
        assertThat(ref.eventType()).isEqualTo("ORDER_CREATED");
        assertThat(ref.occurredAt()).isEqualTo(Instant.parse("2026-08-19T10:00:00Z"));
        assertThat(ref.sequenceNo()).isEqualTo(1L);

        assertThat(events.getFirst().rawPayload()).contains("somethingWeDoNotModelYet");
    }

    @Test
    @DisplayName("an event without a timestamp is still usable for the count and identity checks")
    void tolerates_an_event_without_a_timestamp() {
        ORDER_SERVICE.stubFor(get(urlPathEqualTo("/orders/" + ORDER_ID + "/events"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                [{"eventId": "EVT-1", "eventType": "ORDER_CREATED"}]
                                """)));

        assertThat(orderClient.fetchEvents(ORDER_ID).getFirst().ref().occurredAt())
                .isEqualTo(Instant.EPOCH);
    }

    @Test
    @DisplayName("an event history the audit cannot make sense of is an outage, not an empty history")
    void refuses_an_unreadable_event_history() {
        ORDER_SERVICE.stubFor(get(urlPathEqualTo("/orders/" + ORDER_ID + "/events"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"not\": \"an array\"}")));

        assertThatThrownBy(() -> orderClient.fetchEvents(ORDER_ID))
                .isInstanceOf(SourceUnavailableException.class)
                .hasMessageContaining("non-array");
    }

    @Test
    @DisplayName("an event without an identifier is rejected rather than counted")
    void refuses_an_event_without_an_identifier() {
        ORDER_SERVICE.stubFor(get(urlPathEqualTo("/orders/" + ORDER_ID + "/events"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[{\"eventType\": \"ORDER_CREATED\"}]")));

        assertThatThrownBy(() -> orderClient.fetchEvents(ORDER_ID))
                .isInstanceOf(SourceUnavailableException.class)
                .hasMessageContaining("without eventId");
    }

    @Test
    @DisplayName("an unreadable timestamp is rejected rather than silently reordered")
    void refuses_an_unreadable_timestamp() {
        ORDER_SERVICE.stubFor(get(urlPathEqualTo("/orders/" + ORDER_ID + "/events"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                [{"eventId": "EVT-1", "eventType": "ORDER_CREATED", "occurredAt": "yesterday"}]
                                """)));

        assertThatThrownBy(() -> orderClient.fetchEvents(ORDER_ID))
                .isInstanceOf(SourceUnavailableException.class)
                .hasMessageContaining("occurredAt");
    }

    @Test
    @DisplayName("the event count is read from the dedicated endpoint")
    void reads_the_event_count() {
        orderService.eventCount(ORDER_ID, 7);

        assertThat(orderClient.countEvents(ORDER_ID)).isEqualTo(7);
    }

    @Test
    @DisplayName("no count endpoint for an unknown order means no events")
    void reads_a_missing_count_as_zero() {
        ORDER_SERVICE.stubFor(get(urlPathEqualTo("/orders/" + ORDER_ID + "/events/count"))
                .willReturn(aResponse().withStatus(404)));

        assertThat(orderClient.countEvents(ORDER_ID)).isZero();
    }

    @Test
    @DisplayName("the list of changed orders is followed to the last page")
    void follows_every_page_of_the_change_feed() {
        orderService.modifiedSinceFirstPage("page-2", "ORD-1", "ORD-2");
        orderService.modifiedSinceNextPage("page-2", "ORD-3");

        List<String> ids = orderClient.findOrderIdsModifiedSince(Instant.parse("2026-08-18T00:00:00Z"));

        assertThat(ids).containsExactly("ORD-1", "ORD-2", "ORD-3");
        ORDER_SERVICE.verify(2, WireMock.getRequestedFor(urlPathEqualTo("/orders")));
    }

    @Test
    @DisplayName("a change feed with no page token at all is read as a single page")
    void handles_a_change_feed_that_never_paginates() {
        orderService.modifiedSince("ORD-1", "ORD-2");

        assertThat(orderClient.findOrderIdsModifiedSince(Instant.parse("2026-08-18T00:00:00Z")))
                .containsExactly("ORD-1", "ORD-2");
        ORDER_SERVICE.verify(1, WireMock.getRequestedFor(urlPathEqualTo("/orders")));
    }

    @Test
    @DisplayName("the change feed of the payment system is followed the same way")
    void follows_the_payment_change_feed() {
        paymentService.modifiedSinceFirstPage("p2", "ORD-9");
        paymentService.modifiedSinceNextPage("p2", "ORD-10");

        assertThat(paymentClient.findOrderIdsModifiedSince(Instant.parse("2026-08-18T00:00:00Z")))
                .containsExactly("ORD-9", "ORD-10");
    }

    @Test
    @DisplayName("the change feed passes the audit window to the source")
    void asks_only_for_what_changed_since_the_last_run() {
        orderService.modifiedSince("ORD-1");

        orderClient.findOrderIdsModifiedSince(Instant.parse("2026-08-18T00:00:00Z"));

        ORDER_SERVICE.verify(WireMock.getRequestedFor(urlPathEqualTo("/orders"))
                .withQueryParam("modifiedSince", WireMock.equalTo("2026-08-18T00:00:00Z")));
    }

    @Test
    @DisplayName("a change feed that cannot be read is an error, not an empty day")
    void refuses_an_unavailable_change_feed() {
        orderService.modifiedSinceUnavailable();

        assertThatThrownBy(() -> orderClient.findOrderIdsModifiedSince(Instant.now()))
                .isInstanceOf(SourceUnavailableException.class);
    }

    @Test
    @DisplayName("the payment system is read through the same contract")
    void reads_a_payment() {
        paymentService.aggregate(ORDER_ID, Payloads.payment("PAY-1", ORDER_ID, "PAID", "150.00"));
        paymentService.eventCount(ORDER_ID, 2);

        PaymentSnapshot payment = paymentClient.fetchPayment(ORDER_ID).orElseThrow();

        assertThat(payment.paymentId()).isEqualTo("PAY-1");
        assertThat(payment.status()).isEqualTo(PaymentStatus.PAID);
        assertThat(payment.amount()).isEqualTo(Money.of("150.00", "PLN"));
        assertThat(paymentClient.countEvents(ORDER_ID)).isEqualTo(2);
    }

    @Test
    @DisplayName("an order with no payment comes back as absent")
    void reports_a_missing_payment_as_absent() {
        paymentService.aggregateMissing(ORDER_ID);

        Optional<PaymentSnapshot> payment = paymentClient.fetchPayment(ORDER_ID);

        assertThat(payment).isEmpty();
    }

    @Test
    @DisplayName("payment events carry the payment identifier they belong to")
    void reads_payment_events() {
        paymentService.events(ORDER_ID,
                Payloads.paymentEvent("PAY-EVT-1", "PAY-1", ORDER_ID, "PAYMENT_COMPLETED",
                        "PAID", "150.00", Payloads.T1));

        List<SourceEvent> events = paymentClient.fetchEvents(ORDER_ID);

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.aggregateId()).isEqualTo("PAY-1");
            assertThat(event.orderId()).isEqualTo(ORDER_ID);
            assertThat(event.ref().eventType()).isEqualTo("PAYMENT_COMPLETED");
        });
    }
}
