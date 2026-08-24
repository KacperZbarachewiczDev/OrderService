package com.task.ing.orderaudit.integration;

import com.task.ing.orderaudit.application.port.in.DispatchNotificationsUseCase;
import com.task.ing.orderaudit.support.AbstractIntegrationTest;
import com.task.ing.orderaudit.support.Payloads;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class EndToEndJourneyIT extends AbstractIntegrationTest {

    private static final String ORDER_ID = "ORD-E2E";

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DispatchNotificationsUseCase dispatchNotifications;

    private RestClient client;

    @BeforeEach
    void createClient() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    @DisplayName("a lost event is detected by the audit, reported to an operator, and repaired on request")
    void from_a_lost_event_to_a_closed_issue() throws Exception {
        kafkaPublisher.publish("order-events", ORDER_ID,
                Payloads.orderEventWithItems("EVT-E2E-1", ORDER_ID, "ORDER_CREATED", Payloads.T0,
                        Payloads.item("P-1", 2, "75.00")));
        kafkaPublisher.publish("order-events", ORDER_ID,
                Payloads.orderEvent("EVT-E2E-3", ORDER_ID, "ORDER_COMPLETED", Payloads.T2));
        kafkaPublisher.publish("payment-events", ORDER_ID,
                Payloads.paymentEvent("PAY-EVT-E2E-1", "PAY-E2E", ORDER_ID, "PAYMENT_COMPLETED",
                        "PAID", "150.00", Payloads.T1));

        awaitAssertion(() -> assertThat(jdbcTemplate.queryForObject(
                "select count(*) from ingested_events where order_id = ?", Long.class, ORDER_ID))
                .isEqualTo(3));

        givenTheSourcesHaveEverything();

        ResponseEntity<String> auditRun = post("/api/audit/runs");
        assertThat(auditRun.getStatusCode().value()).isEqualTo(200);
        assertThat(json(auditRun).get("ordersWithIssues").asInt()).isEqualTo(1);

        assertThat(dispatchNotifications.dispatchDue()).isEqualTo(1);
        assertThat(SMTP.getReceivedMessages()).hasSize(1);
        MimeMessage mail = SMTP.getReceivedMessages()[0];
        assertThat(mail.getContent().toString()).contains(ORDER_ID);

        JsonNode issues = json(get("/api/audit/issues"));
        assertThat(issues.get("content").get(0).get("orderId").asString()).isEqualTo(ORDER_ID);

        JsonNode detail = json(get("/api/audit/issues/" + ORDER_ID));
        assertThat(detail.get("discrepancies")).isNotEmpty();
        assertThat(detail.toString()).contains("MISSING_ORDER_EVENTS");

        assertThat(post("/api/audit/issues/" + ORDER_ID + "/resync").getStatusCode().value())
                .isEqualTo(202);

        awaitAssertion(() -> assertThat(
                json(get("/api/audit/issues/" + ORDER_ID + "/resync")).get("status").asString())
                .isEqualTo("SUCCEEDED"));

        assertThat(jdbcTemplate.queryForList(
                "select event_id from ingested_events where order_id = ? and source = 'ORDER'",
                String.class, ORDER_ID))
                .containsExactlyInAnyOrder("EVT-E2E-1", "EVT-E2E-2", "EVT-E2E-3");
        assertThat(json(get("/api/audit/issues/" + ORDER_ID)).get("issue").get("status").asString())
                .isEqualTo("RESOLVED");
        assertThat(json(get("/api/audit/issues")).get("totalElements").asInt()).isZero();
    }

    @Test
    @DisplayName("an order the stream delivered completely never becomes an issue")
    void a_healthy_order_travels_the_whole_way_without_raising_anything() {
        kafkaPublisher.publish("order-events", ORDER_ID,
                Payloads.orderEventWithItems("EVT-E2E-1", ORDER_ID, "ORDER_CREATED", Payloads.T0,
                        Payloads.item("P-1", 2, "75.00")));
        kafkaPublisher.publish("order-events", ORDER_ID,
                Payloads.orderEvent("EVT-E2E-2", ORDER_ID, "ORDER_PAID", Payloads.T1));
        kafkaPublisher.publish("order-events", ORDER_ID,
                Payloads.orderEvent("EVT-E2E-3", ORDER_ID, "ORDER_COMPLETED", Payloads.T2));
        kafkaPublisher.publish("payment-events", ORDER_ID,
                Payloads.paymentEvent("PAY-EVT-E2E-1", "PAY-E2E", ORDER_ID, "PAYMENT_COMPLETED",
                        "PAID", "150.00", Payloads.T1));

        awaitAssertion(() -> assertThat(jdbcTemplate.queryForObject(
                "select count(*) from ingested_events where order_id = ?", Long.class, ORDER_ID))
                .isEqualTo(4));

        givenTheSourcesHaveEverything();

        post("/api/audit/runs");

        assertThat(json(get("/api/audit/issues")).get("totalElements").asInt()).isZero();
        assertThat(dispatchNotifications.dispatchDue()).isZero();
        assertThat(SMTP.getReceivedMessages()).isEmpty();
        assertThat(get("/api/audit/issues/" + ORDER_ID).getStatusCode().value()).isEqualTo(404);
    }

    private void givenTheSourcesHaveEverything() {
        orderService.modifiedSince(ORDER_ID);
        orderService.aggregate(ORDER_ID, Payloads.order(ORDER_ID, "COMPLETED", "150.00",
                Payloads.item("P-1", 2, "75.00")));
        orderService.eventCount(ORDER_ID, 3);
        orderService.events(ORDER_ID,
                Payloads.orderEventWithItems("EVT-E2E-1", ORDER_ID, "ORDER_CREATED", Payloads.T0,
                        Payloads.item("P-1", 2, "75.00")),
                Payloads.orderEvent("EVT-E2E-2", ORDER_ID, "ORDER_PAID", Payloads.T1),
                Payloads.orderEvent("EVT-E2E-3", ORDER_ID, "ORDER_COMPLETED", Payloads.T2));

        paymentService.modifiedSince(ORDER_ID);
        paymentService.aggregate(ORDER_ID, Payloads.payment("PAY-E2E", ORDER_ID, "PAID", "150.00"));
        paymentService.eventCount(ORDER_ID, 1);
        paymentService.events(ORDER_ID,
                Payloads.paymentEvent("PAY-EVT-E2E-1", "PAY-E2E", ORDER_ID, "PAYMENT_COMPLETED",
                        "PAID", "150.00", Payloads.T1));
    }

    private ResponseEntity<String> get(String path) {
        return client.get().uri(path).retrieve()
                .onStatus(status -> true, (request, response) -> {
                })
                .toEntity(String.class);
    }

    private ResponseEntity<String> post(String path) {
        return client.post().uri(path).retrieve()
                .onStatus(status -> true, (request, response) -> {
                })
                .toEntity(String.class);
    }

    private JsonNode json(ResponseEntity<String> response) {
        return objectMapper.readTree(response.getBody());
    }
}
