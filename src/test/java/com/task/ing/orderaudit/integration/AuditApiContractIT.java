package com.task.ing.orderaudit.integration;

import com.task.ing.orderaudit.application.port.in.RunAuditUseCase;
import com.task.ing.orderaudit.application.port.out.SchedulerLockPort;
import com.task.ing.orderaudit.domain.audit.AuditTrigger;
import com.task.ing.orderaudit.support.AbstractIntegrationTest;
import com.task.ing.orderaudit.support.Payloads;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AuditApiContractIT extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RunAuditUseCase runAudit;

    @Autowired
    private SchedulerLockPort schedulerLock;

    private RestClient client;

    @BeforeEach
    void createClient() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    @DisplayName("a negative page number is rejected")
    void rejects_a_negative_page() {
        assertThat(get("/api/audit/issues?page=-1").getStatusCode().is4xxClientError()).isTrue();
    }

    @Test
    @DisplayName("a page size of zero is rejected")
    void rejects_an_empty_page_size() {
        assertThat(get("/api/audit/issues?size=0").getStatusCode().is4xxClientError()).isTrue();
    }

    @Test
    @DisplayName("an unknown status filter is rejected with an explanation")
    void rejects_an_unknown_status_filter() {
        ResponseEntity<String> response = get("/api/audit/issues?status=BROKEN");

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(json(response).get("title").asString()).isEqualTo("Invalid request");
    }

    @Test
    @DisplayName("an unknown severity filter is rejected")
    void rejects_an_unknown_severity_filter() {
        assertThat(get("/api/audit/issues?minSeverity=APOCALYPTIC").getStatusCode().is4xxClientError())
                .isTrue();
    }

    @Test
    @DisplayName("the status filter is not case sensitive")
    void accepts_a_lowercase_status_filter() {
        assertThat(get("/api/audit/issues?status=open").getStatusCode().value()).isEqualTo(200);
        assertThat(get("/api/audit/issues?status=all").getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("an empty issue list is an empty page, not an error")
    void returns_an_empty_page_when_nothing_is_wrong() {
        JsonNode body = json(get("/api/audit/issues"));

        assertThat(body.get("content")).isEmpty();
        assertThat(body.get("totalElements").asInt()).isZero();
        assertThat(body.get("totalPages").asInt()).isZero();
    }

    @Test
    @DisplayName("the most recently detected problem comes first")
    void orders_the_issue_list_by_when_it_was_last_seen() {
        givenABrokenOrder("ORD-C-A");
        givenABrokenOrder("ORD-C-B");
        runAudit.run(AuditTrigger.MANUAL);

        jdbcTemplate.update(
                "update audit_issues set last_detected_at = last_detected_at - interval '1 hour'"
                        + " where order_id = 'ORD-C-B'");

        JsonNode content = json(get("/api/audit/issues")).get("content");

        assertThat(content).hasSize(2);
        assertThat(content.get(0).get("orderId").asString()).isEqualTo("ORD-C-A");
        assertThat(content.get(1).get("orderId").asString()).isEqualTo("ORD-C-B");
    }

    @Test
    @DisplayName("the detail view shows a repair that failed, not only ones that worked")
    void shows_a_failed_repair_in_the_detail_view() {
        String orderId = "ORD-C-C";
        givenABrokenOrder(orderId);
        runAudit.run(AuditTrigger.MANUAL);
        orderService.aggregateUnavailable(orderId);

        post("/api/audit/issues/" + orderId + "/resync");

        awaitAssertion(() -> assertThat(
                json(get("/api/audit/issues/" + orderId)).get("latestResync").get("status").asString())
                .isEqualTo("FAILED"));
        assertThat(json(get("/api/audit/issues/" + orderId)).get("latestResync").get("failureReason")
                .isNull()).isFalse();
    }

    @Test
    @DisplayName("a repair can be requested for an order that has no issue recorded")
    void accepts_a_repair_for_an_unflagged_order() {
        String orderId = "ORD-C-D";
        orderService.aggregate(orderId, Payloads.order(orderId, "COMPLETED", "150.00"));
        orderService.events(orderId, Payloads.orderEvent("EVT-C-D", orderId, "ORDER_CREATED", Payloads.T0));
        orderService.eventCount(orderId, 1);
        paymentService.aggregateMissing(orderId);
        paymentService.events(orderId);
        paymentService.eventCount(orderId, 0);

        assertThat(post("/api/audit/issues/" + orderId + "/resync").getStatusCode().value())
                .isEqualTo(202);
        awaitAssertion(() -> assertThat(
                json(get("/api/audit/issues/" + orderId + "/resync")).get("status").asString())
                .isEqualTo("SUCCEEDED"));
    }

    @Test
    @DisplayName("the run history is paged")
    void pages_the_run_history() {
        orderService.modifiedSince();
        paymentService.modifiedSince();
        runAudit.run(AuditTrigger.MANUAL);
        runAudit.run(AuditTrigger.MANUAL);
        runAudit.run(AuditTrigger.MANUAL);

        JsonNode firstPage = json(get("/api/audit/runs?page=0&size=2"));

        assertThat(firstPage.get("content")).hasSize(2);
        assertThat(firstPage.get("totalElements").asInt()).isEqualTo(3);
        assertThat(json(get("/api/audit/runs?page=1&size=2")).get("content")).hasSize(1);
    }

    @Test
    @DisplayName("an absurd page size on the run history is rejected")
    void rejects_an_absurd_run_page_size() {
        assertThat(get("/api/audit/runs?size=100000").getStatusCode().is4xxClientError()).isTrue();
    }

    @Test
    @DisplayName("an audit cannot be started by hand while another node is running one")
    void refuses_a_manual_run_while_the_lock_is_held() {
        schedulerLock.acquire("daily-audit", "another-node", Instant.now(), Duration.ofMinutes(10));

        assertThat(post("/api/audit/runs").getStatusCode().value()).isEqualTo(409);
        assertThat(count("audit_runs")).isZero();
    }

    @Test
    @DisplayName("errors come back as problem details a client can parse")
    void reports_errors_as_problem_details() {
        ResponseEntity<String> response = get("/api/audit/issues/NOPE");

        assertThat(response.getHeaders().getContentType())
                .hasToString("application/problem+json");
        JsonNode problem = json(response);
        assertThat(problem.get("status").asInt()).isEqualTo(404);
        assertThat(problem.get("title").asString()).isNotEmpty();
    }

    @Test
    @DisplayName("an unknown path is a 404 rather than a server error")
    void reports_an_unknown_path_as_not_found() {
        assertThat(get("/api/audit/nonsense").getStatusCode().value()).isEqualTo(404);
    }

    private void givenABrokenOrder(String orderId) {
        givenReceivedOrderEvent(Payloads.orderEvent("EVT-" + orderId + "-1", orderId, "ORDER_CREATED", Payloads.T0));
        givenReceivedOrderEvent(Payloads.orderEvent("EVT-" + orderId + "-2", orderId, "ORDER_COMPLETED", Payloads.T2));

        orderService.modifiedSince(orderId);
        orderService.aggregate(orderId, Payloads.order(orderId, "CANCELLED", "150.00"));
        orderService.eventCount(orderId, 2);
        orderService.events(orderId,
                Payloads.orderEvent("EVT-" + orderId + "-1", orderId, "ORDER_CREATED", Payloads.T0),
                Payloads.orderEvent("EVT-" + orderId + "-2", orderId, "ORDER_COMPLETED", Payloads.T2));

        paymentService.modifiedSince();
        paymentService.aggregateMissing(orderId);
        paymentService.eventCount(orderId, 0);
        paymentService.events(orderId);
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
