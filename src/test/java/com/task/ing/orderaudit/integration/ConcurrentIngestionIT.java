package com.task.ing.orderaudit.integration;

import com.task.ing.orderaudit.application.port.in.RunAuditUseCase;
import com.task.ing.orderaudit.domain.audit.AuditTrigger;
import com.task.ing.orderaudit.domain.ingest.IngestOutcome;
import com.task.ing.orderaudit.support.AbstractIntegrationTest;
import com.task.ing.orderaudit.support.Payloads;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ConcurrentIngestionIT extends AbstractIntegrationTest {

    @Autowired
    private RunAuditUseCase runAudit;

    @Test
    @DisplayName("the same event handled by eight threads is stored exactly once")
    void stores_a_contended_event_once() throws Exception {
        String orderId = "ORD-C1";
        String event = Payloads.orderEvent("EVT-C1", orderId, "ORDER_CREATED", Payloads.T0);

        List<IngestOutcome> outcomes = inParallel(8, () -> ingest(event));

        assertThat(outcomes).filteredOn(outcome -> outcome == IngestOutcome.APPLIED).hasSize(1);
        assertThat(outcomes).filteredOn(outcome -> outcome == IngestOutcome.DUPLICATE).hasSize(7);
        assertThat(count("ingested_events")).isEqualTo(1);
        assertThat(count("orders")).isEqualTo(1);
    }

    @Test
    @DisplayName("different events for one order all land, whatever order the threads win in")
    void stores_every_distinct_event_of_a_contended_order() throws Exception {
        String orderId = "ORD-C2";
        List<String> events = IntStream.rangeClosed(1, 8)
                .mapToObj(i -> Payloads.orderEvent("EVT-C2-" + i, orderId, "ORDER_UPDATED", Payloads.T0))
                .toList();

        try (ExecutorService threads = Executors.newFixedThreadPool(8)) {
            List<Callable<IngestOutcome>> work = events.stream()
                    .map(event -> (Callable<IngestOutcome>) () -> ingest(event))
                    .toList();
            threads.invokeAll(work).forEach(this::valueOf);
        }

        assertThat(count("ingested_events")).isEqualTo(8);
        assertThat(count("orders")).isEqualTo(1);
    }

    @Test
    @DisplayName("a contended order ends on its newest state, not on whichever thread finished last")
    void never_moves_the_state_backwards_under_contention() throws Exception {
        String orderId = "ORD-C3";
        String older = Payloads.orderEvent("EVT-C3-A", orderId, "ORDER_CREATED", Payloads.T0);
        String newer = Payloads.orderEvent("EVT-C3-B", orderId, "ORDER_COMPLETED", Payloads.T2);

        try (ExecutorService threads = Executors.newFixedThreadPool(8)) {
            List<Callable<IngestOutcome>> work = IntStream.range(0, 8)
                    .mapToObj(i -> (Callable<IngestOutcome>) () -> ingest(i % 2 == 0 ? older : newer))
                    .toList();
            threads.invokeAll(work).forEach(this::valueOf);
        }

        assertThat(count("ingested_events")).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "select status from orders where order_id = ?", String.class, orderId))
                .isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("many orders ingested at once all end up complete")
    void handles_many_orders_at_once() throws Exception {
        List<String> orderIds = IntStream.rangeClosed(1, 16).mapToObj(i -> "ORD-C4-" + i).toList();

        try (ExecutorService threads = Executors.newFixedThreadPool(8)) {
            List<Callable<IngestOutcome>> work = orderIds.stream()
                    .map(orderId -> (Callable<IngestOutcome>) () -> {
                        ingest(Payloads.orderEvent("EVT-" + orderId + "-1", orderId, "ORDER_CREATED", Payloads.T0));
                        return ingest(Payloads.orderEvent("EVT-" + orderId + "-2", orderId, "ORDER_COMPLETED", Payloads.T2));
                    })
                    .toList();
            threads.invokeAll(work).forEach(this::valueOf);
        }

        assertThat(count("orders")).isEqualTo(16);
        assertThat(count("ingested_events")).isEqualTo(32);
    }

    @Test
    @DisplayName("ingestion continues while an audit is in progress")
    void ingests_while_an_audit_runs() throws Exception {
        String auditedOrder = "ORD-C5";
        givenReceivedOrderEvent(Payloads.orderEvent("EVT-C5-1", auditedOrder, "ORDER_CREATED", Payloads.T0));
        orderService.modifiedSince(auditedOrder);
        orderService.aggregate(auditedOrder, Payloads.order(auditedOrder, "CREATED", "150.00"));
        orderService.eventCount(auditedOrder, 1);
        orderService.events(auditedOrder,
                Payloads.orderEvent("EVT-C5-1", auditedOrder, "ORDER_CREATED", Payloads.T0));
        paymentService.modifiedSince();
        paymentService.aggregateMissing(auditedOrder);
        paymentService.eventCount(auditedOrder, 0);
        paymentService.events(auditedOrder);

        try (ExecutorService threads = Executors.newFixedThreadPool(2)) {
            Future<?> audit = threads.submit(() -> runAudit.run(AuditTrigger.MANUAL));
            Future<?> ingestion = threads.submit(() -> IntStream.rangeClosed(1, 20).forEach(i ->
                    ingest(Payloads.orderEvent("EVT-C5-OTHER-" + i, "ORD-C5-OTHER-" + i,
                            "ORDER_CREATED", Payloads.T0))));
            audit.get();
            ingestion.get();
        }

        assertThat(count("orders")).isEqualTo(21);
        assertThat(count("audit_runs")).isEqualTo(1);
    }

    private IngestOutcome ingest(String json) {
        return ingestEvents.ingestOrderEvent(kafkaEventMapper.toOrderCommand(json, Instant.now()));
    }

    private List<IngestOutcome> inParallel(int threadCount, Callable<IngestOutcome> work) throws Exception {
        try (ExecutorService threads = Executors.newFixedThreadPool(threadCount)) {
            List<Callable<IngestOutcome>> attempts = IntStream.range(0, threadCount)
                    .mapToObj(i -> work)
                    .toList();
            return threads.invokeAll(attempts).stream().map(this::valueOf).toList();
        }
    }

    private <T> T valueOf(Future<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }
}
