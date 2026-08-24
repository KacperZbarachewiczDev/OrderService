package com.task.ing.orderaudit.integration;

import com.task.ing.orderaudit.application.port.in.RunAuditUseCase;
import com.task.ing.orderaudit.domain.audit.AuditTrigger;
import com.task.ing.orderaudit.support.AbstractIntegrationTest;
import com.task.ing.orderaudit.support.Payloads;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class AuditApiIT extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private RunAuditUseCase runAudit;

    @Autowired
    private ObjectMapper objectMapper;

    private RestClient client;

    @BeforeEach
    void createClient() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    @DisplayName("the issue list shows the orders that need attention")
    void lists_open_issues() {
        givenABrokenOrder("ORD-W1");
        runAudit.run(AuditTrigger.MANUAL);

        JsonNode body = json(get("/api/audit/issues"));

        assertThat(body.get("totalElements").asInt()).isEqualTo(1);
        JsonNode issue = body.get("content").get(0);
        assertThat(issue.get("orderId").asString()).isEqualTo("ORD-W1");
        assertThat(issue.get("status").asString()).isEqualTo("OPEN");
        assertThat(issue.get("highestSeverity").asString()).isEqualTo("CRITICAL");
        assertThat(issue.get("discrepancyCount").asInt()).isPositive();
    }

    @Test
    @DisplayName("resolved orders are hidden by default and available on request")
    void filters_by_status() {
        givenABrokenOrder("ORD-W2");
        runAudit.run(AuditTrigger.MANUAL);
        orderService.aggregate("ORD-W2", Payloads.order("ORD-W2", "COMPLETED", "150.00"));
        runAudit.run(AuditTrigger.MANUAL);

        assertThat(json(get("/api/audit/issues")).get("totalElements").asInt()).isZero();
        assertThat(json(get("/api/audit/issues?status=RESOLVED")).get("totalElements").asInt()).isEqualTo(1);
        assertThat(json(get("/api/audit/issues?status=ALL")).get("totalElements").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("the list can be narrowed to the findings worth waking someone for")
    void filters_by_severity() {
        givenABrokenOrder("ORD-W3");
        runAudit.run(AuditTrigger.MANUAL);

        assertThat(json(get("/api/audit/issues?minSeverity=CRITICAL")).get("totalElements").asInt())
                .isEqualTo(1);
        assertThat(json(get("/api/audit/issues?minSeverity=MINOR")).get("totalElements").asInt())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the list is paged")
    void pages_the_issue_list() {
        for (int i = 1; i <= 3; i++) {
            givenABrokenOrder("ORD-WP" + i);
        }
        runAudit.run(AuditTrigger.MANUAL);

        JsonNode firstPage = json(get("/api/audit/issues?page=0&size=2"));
        assertThat(firstPage.get("content")).hasSize(2);
        assertThat(firstPage.get("totalElements").asInt()).isEqualTo(3);
        assertThat(firstPage.get("totalPages").asInt()).isEqualTo(2);
        assertThat(json(get("/api/audit/issues?page=1&size=2")).get("content")).hasSize(1);
    }

    @Test
    @DisplayName("the detail view names every difference found")
    void shows_the_findings_behind_an_issue() {
        givenABrokenOrder("ORD-W4");
        runAudit.run(AuditTrigger.MANUAL);

        JsonNode body = json(get("/api/audit/issues/ORD-W4"));

        assertThat(body.get("issue").get("orderId").asString()).isEqualTo("ORD-W4");
        JsonNode discrepancies = body.get("discrepancies");
        assertThat(discrepancies).isNotEmpty();
        JsonNode statusMismatch = findByType(discrepancies, "ORDER_STATUS_MISMATCH");
        assertThat(statusMismatch.get("severity").asString()).isEqualTo("CRITICAL");
        assertThat(statusMismatch.get("expected").asString()).isEqualTo("CANCELLED");
        assertThat(statusMismatch.get("actual").asString()).isEqualTo("COMPLETED");
        assertThat(statusMismatch.get("description").asString()).contains("ORDER_STATUS_MISMATCH");
        assertThat(body.get("latestResync").isNull()).isTrue();
    }

    @Test
    @DisplayName("asking about an order with no audit history is a 404 with a readable body")
    void reports_an_unknown_order_as_not_found() {
        ResponseEntity<String> response = get("/api/audit/issues/ORD-DOES-NOT-EXIST");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        JsonNode problem = json(response);
        assertThat(problem.get("title").asString()).isEqualTo("Audit issue not found");
        assertThat(problem.get("detail").asString()).contains("ORD-DOES-NOT-EXIST");
    }

    @Test
    @DisplayName("a repair is accepted immediately and its outcome is polled")
    void accepts_a_resync_request_and_reports_its_progress() {
        String orderId = "ORD-W5";
        givenABrokenOrder(orderId);
        runAudit.run(AuditTrigger.MANUAL);
        orderService.aggregate(orderId, Payloads.order(orderId, "COMPLETED", "150.00"));

        ResponseEntity<String> accepted = post("/api/audit/issues/" + orderId + "/resync");

        assertThat(accepted.getStatusCode().value()).isEqualTo(202);
        JsonNode job = json(accepted);
        assertThat(job.get("orderId").asString()).isEqualTo(orderId);
        assertThat(job.get("jobId").asLong()).isPositive();

        awaitAssertion(() -> assertThat(
                json(get("/api/audit/issues/" + orderId + "/resync")).get("status").asString())
                .isEqualTo("SUCCEEDED"));

        JsonNode finished = json(get("/api/audit/issues/" + orderId + "/resync"));
        assertThat(finished.get("remainingDiscrepancies").asInt()).isZero();
        assertThat(finished.get("finishedAt").isNull()).isFalse();

        JsonNode detail = json(get("/api/audit/issues/" + orderId));
        assertThat(detail.get("issue").get("status").asString()).isEqualTo("RESOLVED");
        assertThat(detail.get("latestResync").get("status").asString()).isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("asking for the status of a repair that was never requested is a 404")
    void reports_a_missing_resync_as_not_found() {
        assertThat(get("/api/audit/issues/ORD-NOTHING/resync").getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("the run history shows what each audit did")
    void lists_the_audit_runs() {
        orderService.modifiedSince();
        paymentService.modifiedSince();
        runAudit.run(AuditTrigger.MANUAL);
        runAudit.run(AuditTrigger.MANUAL);

        JsonNode body = json(get("/api/audit/runs"));

        assertThat(body.get("totalElements").asInt()).isEqualTo(2);
        JsonNode newest = body.get("content").get(0);
        assertThat(newest.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(newest.get("trigger").asString()).isEqualTo("MANUAL");
        assertThat(newest.get("windowTo").isNull()).isFalse();
    }

    @Test
    @DisplayName("an audit can be started on demand rather than waiting for the night")
    void triggers_an_audit_on_demand() {
        givenABrokenOrder("ORD-W6");

        ResponseEntity<String> response = post("/api/audit/runs");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode run = json(response);
        assertThat(run.get("ordersWithIssues").asInt()).isEqualTo(1);
        assertThat(json(get("/api/audit/issues")).get("totalElements").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("a nonsensical page size is rejected rather than quietly clamped")
    void rejects_an_invalid_page_size() {
        HttpStatusCode status = get("/api/audit/issues?size=99999").getStatusCode();

        assertThat(status.is4xxClientError()).isTrue();
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

    private JsonNode findByType(JsonNode discrepancies, String type) {
        for (JsonNode node : discrepancies) {
            if (type.equals(node.get("type").asString())) {
                return node;
            }
        }
        throw new AssertionError("no discrepancy of type " + type + " in " + discrepancies);
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
