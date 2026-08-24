package com.task.ing.orderaudit.integration;

import com.task.ing.orderaudit.application.port.out.AuditIssuePort;
import com.task.ing.orderaudit.application.port.out.AuditRunPort;
import com.task.ing.orderaudit.application.port.out.EventStorePort;
import com.task.ing.orderaudit.application.port.out.NotificationOutboxPort;
import com.task.ing.orderaudit.application.port.out.OrderProjectionPort;
import com.task.ing.orderaudit.application.port.out.PageResult;
import com.task.ing.orderaudit.application.port.out.Pagination;
import com.task.ing.orderaudit.application.port.out.PaymentProjectionPort;
import com.task.ing.orderaudit.application.port.out.ResyncJobPort;
import com.task.ing.orderaudit.domain.audit.AuditIssue;
import com.task.ing.orderaudit.domain.audit.AuditIssueDetail;
import com.task.ing.orderaudit.domain.audit.AuditRun;
import com.task.ing.orderaudit.domain.audit.AuditRunStats;
import com.task.ing.orderaudit.domain.audit.AuditTrigger;
import com.task.ing.orderaudit.domain.audit.Discrepancy;
import com.task.ing.orderaudit.domain.audit.DiscrepancyType;
import com.task.ing.orderaudit.domain.audit.IssueStatus;
import com.task.ing.orderaudit.domain.audit.Severity;
import com.task.ing.orderaudit.domain.model.EventOrigin;
import com.task.ing.orderaudit.domain.model.EventRef;
import com.task.ing.orderaudit.domain.model.EventSource;
import com.task.ing.orderaudit.domain.model.IngestedEvent;
import com.task.ing.orderaudit.domain.model.Money;
import com.task.ing.orderaudit.domain.model.OrderLine;
import com.task.ing.orderaudit.domain.model.OrderProjection;
import com.task.ing.orderaudit.domain.model.OrderSnapshot;
import com.task.ing.orderaudit.domain.model.OrderStatus;
import com.task.ing.orderaudit.domain.model.PaymentProjection;
import com.task.ing.orderaudit.domain.model.PaymentSnapshot;
import com.task.ing.orderaudit.domain.model.PaymentStatus;
import com.task.ing.orderaudit.domain.notification.NotificationMessage;
import com.task.ing.orderaudit.domain.resync.ResyncJob;
import com.task.ing.orderaudit.domain.resync.ResyncStatus;
import com.task.ing.orderaudit.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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

class PersistenceIT extends AbstractIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-19T12:00:00Z");
    private static final String ORDER_ID = "ORD-DB1";

    @Autowired
    private OrderProjectionPort orderProjections;
    @Autowired
    private PaymentProjectionPort paymentProjections;
    @Autowired
    private EventStorePort eventStore;
    @Autowired
    private AuditIssuePort auditIssues;
    @Autowired
    private AuditRunPort auditRuns;
    @Autowired
    private ResyncJobPort resyncJobs;
    @Autowired
    private NotificationOutboxPort outbox;

    @Nested
    @DisplayName("order projection")
    class OrderProjectionAdapter {
        @Test
        void stores_and_reads_back_an_order_with_its_lines() {
            orderProjections.save(projection(OrderStatus.CREATED, "150.00",
                    new OrderLine("P-1", 2, Money.of("75.00", "PLN"))), NOW);

            OrderProjection stored = orderProjections.find(ORDER_ID).orElseThrow();

            assertThat(stored.snapshot().status()).isEqualTo(OrderStatus.CREATED);
            assertThat(stored.snapshot().totalAmount()).isEqualTo(Money.of("150.00", "PLN"));
            assertThat(stored.snapshot().lines()).containsExactly(
                    new OrderLine("P-1", 2, Money.of("75.00", "PLN")));
            assertThat(stored.lastAppliedEvent().eventId()).isEqualTo("EVT-1");
        }

        @Test
        void updates_an_existing_order_instead_of_creating_a_second_one() {
            orderProjections.save(projection(OrderStatus.CREATED, "150.00"), NOW);
            orderProjections.save(projection(OrderStatus.COMPLETED, "150.00"), NOW.plusSeconds(60));

            assertThat(count("orders")).isEqualTo(1);
            assertThat(orderProjections.find(ORDER_ID).orElseThrow().snapshot().status())
                    .isEqualTo(OrderStatus.COMPLETED);
        }

        @Test
        void keeps_the_moment_the_order_was_first_seen() {
            orderProjections.save(projection(OrderStatus.CREATED, "150.00"), NOW);
            orderProjections.save(projection(OrderStatus.COMPLETED, "150.00"), NOW.plusSeconds(60));

            assertThat(jdbcTemplate.queryForObject(
                    "select first_seen_at from orders where order_id = ?", Instant.class, ORDER_ID))
                    .isEqualTo(NOW);
        }

        @Test
        void leaves_unchanged_lines_alone() {
            OrderLine line = new OrderLine("P-1", 2, Money.of("75.00", "PLN"));
            orderProjections.save(projection(OrderStatus.CREATED, "150.00", line), NOW);
            Long lineId = jdbcTemplate.queryForObject(
                    "select id from order_lines where order_id = ?", Long.class, ORDER_ID);

            orderProjections.save(projection(OrderStatus.COMPLETED, "150.00", line), NOW.plusSeconds(60));

            assertThat(jdbcTemplate.queryForObject(
                    "select id from order_lines where order_id = ?", Long.class, ORDER_ID))
                    .isEqualTo(lineId);
        }

        @Test
        void replaces_lines_that_really_changed() {
            orderProjections.save(projection(OrderStatus.CREATED, "150.00",
                    new OrderLine("P-1", 2, Money.of("75.00", "PLN"))), NOW);

            orderProjections.save(projection(OrderStatus.CREATED, "160.00",
                    new OrderLine("P-2", 1, Money.of("160.00", "PLN"))), NOW.plusSeconds(60));

            assertThat(orderProjections.find(ORDER_ID).orElseThrow().snapshot().lines())
                    .extracting(OrderLine::productId).containsExactly("P-2");
            assertThat(count("order_lines")).isEqualTo(1);
        }

        @Test
        void pages_through_recently_changed_orders_by_key() {
            IntStream.rangeClosed(1, 5).forEach(i -> orderProjections.save(
                    new OrderProjection(
                            new OrderSnapshot("ORD-P" + i, "CUST-1", OrderStatus.CREATED, null, List.of(), NOW),
                            EventRef.of("EVT-" + i, "ORDER_CREATED", NOW)),
                    NOW));

            List<String> firstPage = orderProjections.findOrderIdsUpdatedBetween(
                    NOW.minusSeconds(1), NOW.plusSeconds(1), 2, null);
            List<String> secondPage = orderProjections.findOrderIdsUpdatedBetween(
                    NOW.minusSeconds(1), NOW.plusSeconds(1), 2, firstPage.getLast());

            assertThat(firstPage).containsExactly("ORD-P1", "ORD-P2");
            assertThat(secondPage).containsExactly("ORD-P3", "ORD-P4");
        }

        @Test
        void ignores_orders_outside_the_audit_window() {
            orderProjections.save(projection(OrderStatus.CREATED, "150.00"), NOW.minusSeconds(3600));

            assertThat(orderProjections.findOrderIdsUpdatedBetween(
                    NOW.minusSeconds(60), NOW.plusSeconds(60), 10, null)).isEmpty();
        }

        @Test
        void deleting_an_order_takes_its_lines_with_it() {
            orderProjections.save(projection(OrderStatus.CREATED, "150.00",
                    new OrderLine("P-1", 2, Money.of("75.00", "PLN"))), NOW);
            assertThat(count("order_lines")).isEqualTo(1);

            jdbcTemplate.update("delete from orders where order_id = ?", ORDER_ID);

            assertThat(count("order_lines")).isZero();
        }

        private OrderProjection projection(OrderStatus status, String total, OrderLine... lines) {
            return new OrderProjection(
                    new OrderSnapshot(ORDER_ID, "CUST-1", status, Money.of(total, "PLN"),
                            List.of(lines), NOW),
                    EventRef.of("EVT-1", "ORDER_CREATED", NOW));
        }
    }

    @Nested
    @DisplayName("payment projection")
    class PaymentProjectionAdapter {
        @Test
        void stores_and_reads_back_a_payment() {
            paymentProjections.save(new PaymentProjection(
                    new PaymentSnapshot("PAY-1", ORDER_ID, PaymentStatus.PAID,
                            Money.of("150.00", "PLN"), NOW),
                    EventRef.of("PAY-EVT-1", "PAYMENT_COMPLETED", NOW)), NOW);

            PaymentProjection stored = paymentProjections.findByOrderId(ORDER_ID).orElseThrow();

            assertThat(stored.snapshot().paymentId()).isEqualTo("PAY-1");
            assertThat(stored.snapshot().amount()).isEqualTo(Money.of("150.00", "PLN"));
            assertThat(stored.lastAppliedEvent().eventId()).isEqualTo("PAY-EVT-1");
        }

        @Test
        void reports_no_payment_for_an_order_that_has_none() {
            assertThat(paymentProjections.findByOrderId("ORD-NOTHING")).isEmpty();
        }
    }

    @Nested
    @DisplayName("event archive")
    class EventStoreAdapter {
        @Test
        void stores_a_new_event_and_rejects_the_same_one_again() {
            assertThat(eventStore.append(event("EVT-1", EventSource.ORDER))).isTrue();
            assertThat(eventStore.append(event("EVT-1", EventSource.ORDER))).isFalse();
            assertThat(count("ingested_events")).isEqualTo(1);
        }

        @Test
        void keeps_the_two_source_systems_in_separate_identifier_spaces() {
            assertThat(eventStore.append(event("EVT-1", EventSource.ORDER))).isTrue();
            assertThat(eventStore.append(event("EVT-1", EventSource.PAYMENT))).isTrue();

            assertThat(eventStore.findEventRefs(ORDER_ID, EventSource.ORDER)).hasSize(1);
            assertThat(eventStore.findEventRefs(ORDER_ID, EventSource.PAYMENT)).hasSize(1);
        }

        @Test
        void returns_the_history_in_chronological_order() {
            eventStore.append(event("EVT-2", EventSource.ORDER, NOW.plusSeconds(60)));
            eventStore.append(event("EVT-1", EventSource.ORDER, NOW));

            assertThat(eventStore.findEventRefs(ORDER_ID, EventSource.ORDER))
                    .extracting(EventRef::eventId).containsExactly("EVT-1", "EVT-2");
        }

        @Test
        void lets_only_one_of_two_concurrent_writers_store_the_same_event() throws Exception {
            try (ExecutorService writers = Executors.newFixedThreadPool(4)) {
                List<Callable<Boolean>> attempts = IntStream.range(0, 4)
                        .mapToObj(i -> (Callable<Boolean>) () -> eventStore.append(event("EVT-RACE", EventSource.ORDER)))
                        .toList();

                long stored = writers.invokeAll(attempts).stream().filter(PersistenceIT.this::valueOf).count();

                assertThat(stored).isEqualTo(1);
                assertThat(count("ingested_events")).isEqualTo(1);
            }
        }

        private IngestedEvent event(String eventId, EventSource source) {
            return event(eventId, source, NOW);
        }

        private IngestedEvent event(String eventId, EventSource source, Instant occurredAt) {
            return new IngestedEvent(eventId, source, ORDER_ID, ORDER_ID, "ORDER_CREATED",
                    occurredAt, null, "{\"eventId\":\"" + eventId + "\"}", NOW, EventOrigin.KAFKA);
        }
    }

    @Nested
    @DisplayName("audit issues")
    class AuditIssueAdapter {
        @Test
        void records_an_issue_with_its_findings() {
            auditIssues.record(ORDER_ID, List.of(statusMismatch(), customerMismatch()), null, NOW);

            AuditIssueDetail detail = auditIssues.findDetail(ORDER_ID).orElseThrow();

            assertThat(detail.issue().status()).isEqualTo(IssueStatus.OPEN);
            assertThat(detail.issue().highestSeverity()).isEqualTo(Severity.CRITICAL);
            assertThat(detail.issue().discrepancyCount()).isEqualTo(2);
            assertThat(detail.discrepancies()).hasSize(2);
        }

        @Test
        void replaces_the_findings_of_a_recurring_issue_rather_than_appending() {
            auditIssues.record(ORDER_ID, List.of(statusMismatch(), customerMismatch()), null, NOW);
            auditIssues.record(ORDER_ID, List.of(statusMismatch()), null, NOW.plusSeconds(3600));

            AuditIssueDetail detail = auditIssues.findDetail(ORDER_ID).orElseThrow();
            assertThat(detail.discrepancies()).hasSize(1);
            assertThat(detail.issue().discrepancyCount()).isEqualTo(1);
            assertThat(count("audit_issues")).isEqualTo(1);
        }

        @Test
        void keeps_the_moment_the_problem_was_first_noticed() {
            auditIssues.record(ORDER_ID, List.of(statusMismatch()), null, NOW);
            auditIssues.record(ORDER_ID, List.of(statusMismatch()), null, NOW.plusSeconds(3600));

            AuditIssue issue = auditIssues.findDetail(ORDER_ID).orElseThrow().issue();
            assertThat(issue.firstDetectedAt()).isEqualTo(NOW);
            assertThat(issue.lastDetectedAt()).isEqualTo(NOW.plusSeconds(3600));
        }

        @Test
        void closing_an_issue_clears_its_findings() {
            auditIssues.record(ORDER_ID, List.of(statusMismatch()), null, NOW);

            assertThat(auditIssues.resolve(ORDER_ID, NOW.plusSeconds(60))).isTrue();

            AuditIssueDetail detail = auditIssues.findDetail(ORDER_ID).orElseThrow();
            assertThat(detail.issue().status()).isEqualTo(IssueStatus.RESOLVED);
            assertThat(detail.discrepancies()).isEmpty();
            assertThat(count("audit_discrepancies")).isZero();
        }

        @Test
        void closing_an_issue_that_is_not_open_changes_nothing() {
            assertThat(auditIssues.resolve("ORD-NEVER-BROKEN", NOW)).isFalse();

            auditIssues.record(ORDER_ID, List.of(statusMismatch()), null, NOW);
            auditIssues.resolve(ORDER_ID, NOW);
            assertThat(auditIssues.resolve(ORDER_ID, NOW)).isFalse();
        }

        @Test
        void a_reopened_issue_becomes_open_again() {
            auditIssues.record(ORDER_ID, List.of(statusMismatch()), null, NOW);
            auditIssues.resolve(ORDER_ID, NOW.plusSeconds(60));

            auditIssues.record(ORDER_ID, List.of(statusMismatch()), null, NOW.plusSeconds(120));

            AuditIssue issue = auditIssues.findDetail(ORDER_ID).orElseThrow().issue();
            assertThat(issue.status()).isEqualTo(IssueStatus.OPEN);
            assertThat(issue.resolvedAt()).isNull();
        }

        @Test
        void filters_the_list_by_status_and_severity() {
            auditIssues.record("ORD-CRIT", List.of(statusMismatch()), null, NOW);
            auditIssues.record("ORD-MAJOR", List.of(customerMismatch()), null, NOW);
            auditIssues.record("ORD-DONE", List.of(statusMismatch()), null, NOW);
            auditIssues.resolve("ORD-DONE", NOW);

            PageResult<AuditIssue> open = auditIssues.findAll(IssueStatus.OPEN, null, Pagination.of(0, 10));
            PageResult<AuditIssue> critical = auditIssues.findAll(null, Severity.CRITICAL, Pagination.of(0, 10));

            assertThat(open.content()).extracting(AuditIssue::orderId)
                    .containsExactlyInAnyOrder("ORD-CRIT", "ORD-MAJOR");
            assertThat(critical.content()).extracting(AuditIssue::orderId)
                    .containsExactlyInAnyOrder("ORD-CRIT", "ORD-DONE");
        }

        @Test
        void pages_through_open_issues_for_the_next_audit() {
            IntStream.rangeClosed(1, 4).forEach(i ->
                    auditIssues.record("ORD-O" + i, List.of(statusMismatch()), null, NOW));

            assertThat(auditIssues.findOpenOrderIds(2, null)).containsExactly("ORD-O1", "ORD-O2");
            assertThat(auditIssues.findOpenOrderIds(2, "ORD-O2")).containsExactly("ORD-O3", "ORD-O4");
            assertThat(auditIssues.countOpen()).isEqualTo(4);
        }

        @Test
        void deleting_an_issue_takes_its_findings_with_it() {
            auditIssues.record(ORDER_ID, List.of(statusMismatch(), customerMismatch()), null, NOW);
            assertThat(count("audit_discrepancies")).isEqualTo(2);

            jdbcTemplate.update("delete from audit_issues where order_id = ?", ORDER_ID);

            assertThat(count("audit_discrepancies")).isZero();
        }

        private Discrepancy statusMismatch() {
            return Discrepancy.of(DiscrepancyType.ORDER_STATUS_MISMATCH, "status", "PAID", "CREATED");
        }

        private Discrepancy customerMismatch() {
            return Discrepancy.of(DiscrepancyType.ORDER_CUSTOMER_MISMATCH, "customerId", "A", "B");
        }
    }

    @Nested
    @DisplayName("audit runs")
    class AuditRunAdapter {
        @Test
        void records_a_run_from_start_to_finish() {
            AuditRun started = auditRuns.start(AuditTrigger.SCHEDULED, NOW.minusSeconds(86_400), NOW, NOW);

            AuditRun finished = auditRuns.complete(
                    started.id(), new AuditRunStats(10, 2, 1, 5, 1), NOW.plusSeconds(60));

            assertThat(finished.stats().ordersChecked()).isEqualTo(10);
            assertThat(finished.stats().ordersInconclusive()).isEqualTo(1);
            assertThat(auditRuns.findById(started.id())).isPresent();
        }

        @Test
        void only_a_completed_run_moves_the_watermark() {
            AuditRun failed = auditRuns.start(AuditTrigger.SCHEDULED, NOW.minusSeconds(7200), NOW, NOW);
            auditRuns.fail(failed.id(), "source down", NOW.plusSeconds(10));

            assertThat(auditRuns.lastCompletedWindowEnd()).isEmpty();

            AuditRun completed = auditRuns.start(
                    AuditTrigger.SCHEDULED, NOW.minusSeconds(7200), NOW.plusSeconds(20), NOW.plusSeconds(20));
            auditRuns.complete(completed.id(), AuditRunStats.empty(), NOW.plusSeconds(30));

            assertThat(auditRuns.lastCompletedWindowEnd()).contains(NOW.plusSeconds(20));
        }

        @Test
        void lists_the_history_newest_first() {
            auditRuns.start(AuditTrigger.SCHEDULED, NOW.minusSeconds(7200), NOW, NOW);
            auditRuns.start(AuditTrigger.MANUAL, NOW, NOW.plusSeconds(60), NOW.plusSeconds(60));

            PageResult<AuditRun> page = auditRuns.findAll(Pagination.of(0, 10));

            assertThat(page.totalElements()).isEqualTo(2);
            assertThat(page.content().getFirst().trigger()).isEqualTo(AuditTrigger.MANUAL);
        }

        @Test
        void records_why_a_run_failed() {
            AuditRun run = auditRuns.start(AuditTrigger.SCHEDULED, NOW.minusSeconds(7200), NOW, NOW);

            auditRuns.fail(run.id(), "everything went wrong", NOW.plusSeconds(5));

            assertThat(auditRuns.findById(run.id()).orElseThrow().failureReason())
                    .isEqualTo("everything went wrong");
        }
    }

    @Nested
    @DisplayName("resync jobs")
    class ResyncJobAdapter {
        @Test
        void allows_only_one_job_in_flight_per_order() {
            ResyncJob first = resyncJobs.enqueue(ORDER_ID, NOW);
            ResyncJob second = resyncJobs.enqueue(ORDER_ID, NOW.plusSeconds(1));

            assertThat(second.id()).isEqualTo(first.id());
            assertThat(count("resync_jobs")).isEqualTo(1);
        }

        @Test
        void allows_a_new_job_once_the_previous_one_finished() {
            ResyncJob first = resyncJobs.enqueue(ORDER_ID, NOW);
            resyncJobs.claim(first.id(), NOW);
            resyncJobs.succeed(first.id(), 0, NOW.plusSeconds(5));

            ResyncJob second = resyncJobs.enqueue(ORDER_ID, NOW.plusSeconds(10));

            assertThat(second.id()).isNotEqualTo(first.id());
            assertThat(count("resync_jobs")).isEqualTo(2);
        }

        @Test
        void lets_exactly_one_worker_claim_a_job() throws Exception {
            ResyncJob job = resyncJobs.enqueue(ORDER_ID, NOW);

            try (ExecutorService workers = Executors.newFixedThreadPool(4)) {
                List<Callable<Boolean>> attempts = IntStream.range(0, 4)
                        .mapToObj(i -> (Callable<Boolean>) () -> resyncJobs.claim(job.id(), NOW).isPresent())
                        .toList();

                long claimed = workers.invokeAll(attempts).stream().filter(PersistenceIT.this::valueOf).count();

                assertThat(claimed).isEqualTo(1);
            }
        }

        @Test
        void counts_the_attempts_made_on_a_job() {
            ResyncJob job = resyncJobs.enqueue(ORDER_ID, NOW);

            assertThat(resyncJobs.claim(job.id(), NOW)).get()
                    .extracting(ResyncJob::attempts).isEqualTo(1);
        }

        @Test
        void records_the_outcome_of_a_repair() {
            ResyncJob job = resyncJobs.enqueue(ORDER_ID, NOW);
            resyncJobs.claim(job.id(), NOW);

            resyncJobs.succeed(job.id(), 3, NOW.plusSeconds(5));

            ResyncJob finished = resyncJobs.findById(job.id()).orElseThrow();
            assertThat(finished.status()).isEqualTo(ResyncStatus.SUCCEEDED);
            assertThat(finished.remainingDiscrepancies()).isEqualTo(3);
            assertThat(finished.finishedAt()).isEqualTo(NOW.plusSeconds(5));
        }

        @Test
        void records_why_a_repair_failed() {
            ResyncJob job = resyncJobs.enqueue(ORDER_ID, NOW);
            resyncJobs.claim(job.id(), NOW);

            resyncJobs.fail(job.id(), "order-service unreachable", NOW.plusSeconds(5));

            assertThat(resyncJobs.findById(job.id()).orElseThrow().failureReason())
                    .isEqualTo("order-service unreachable");
        }

        @Test
        void finds_jobs_a_crashed_node_left_behind() {
            resyncJobs.enqueue(ORDER_ID, NOW.minusSeconds(600));
            resyncJobs.enqueue("ORD-FRESH", NOW);

            List<Long> stale = resyncJobs.findStalePendingJobIds(NOW.minusSeconds(60), 10);

            assertThat(stale).hasSize(1);
            assertThat(resyncJobs.findById(stale.getFirst()).orElseThrow().orderId()).isEqualTo(ORDER_ID);
        }
    }

    @Nested
    @DisplayName("notification outbox")
    class NotificationOutboxAdapter {
        @Test
        void stores_a_message_and_hands_it_out_when_due() {
            outbox.enqueue(message(), null, NOW);

            List<NotificationOutboxPort.PendingNotification> due = outbox.claimDue(NOW, 10);

            assertThat(due).hasSize(1);
            assertThat(due.getFirst().message().subject()).isEqualTo("audit findings");
            assertThat(due.getFirst().message().recipients()).containsExactly("a@example.com", "b@example.com");
            assertThat(due.getFirst().attempts()).isEqualTo(1);
        }

        @Test
        void does_not_hand_the_same_message_to_a_second_pass() {
            outbox.enqueue(message(), null, NOW);
            outbox.claimDue(NOW, 10);

            assertThat(outbox.claimDue(NOW, 10)).isEmpty();
        }

        @Test
        void lets_two_workers_drain_the_outbox_without_overlapping() throws Exception {
            IntStream.rangeClosed(1, 6).forEach(i -> outbox.enqueue(message(), null, NOW));

            try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
                List<Future<Integer>> claims = workers.invokeAll(List.of(
                        () -> outbox.claimDue(NOW, 10).size(),
                        () -> outbox.claimDue(NOW, 10).size()));

                int total = claims.stream().mapToInt(PersistenceIT.this::intValueOf).sum();
                assertThat(total).isEqualTo(6);
            }
        }

        @Test
        void marks_a_delivered_message_as_sent() {
            long id = outbox.enqueue(message(), null, NOW);

            outbox.markSent(id, NOW.plusSeconds(1));

            assertThat(jdbcTemplate.queryForObject(
                    "select status from notification_outbox where id = ?", String.class, id))
                    .isEqualTo("SENT");
        }

        @Test
        void pushes_a_failed_message_into_the_future() {
            long id = outbox.enqueue(message(), null, NOW);
            outbox.claimDue(NOW, 10);

            outbox.reschedule(id, "smtp refused", NOW.plusSeconds(30));

            assertThat(outbox.claimDue(NOW.plusSeconds(10), 10)).isEmpty();
            assertThat(outbox.claimDue(NOW.plusSeconds(31), 10)).hasSize(1);
        }

        @Test
        void parks_a_message_nobody_can_deliver() {
            long id = outbox.enqueue(message(), null, NOW);

            outbox.markFailed(id, "gave up", NOW.plusSeconds(1));

            assertThat(outbox.claimDue(NOW.plusSeconds(3600), 10)).isEmpty();
            assertThat(jdbcTemplate.queryForObject(
                    "select status from notification_outbox where id = ?", String.class, id))
                    .isEqualTo("FAILED");
        }

        private NotificationMessage message() {
            return new NotificationMessage(
                    List.of("a@example.com", "b@example.com"), "audit findings", "body");
        }
    }

    private boolean valueOf(Future<Boolean> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException(e);
        }
    }

    private int intValueOf(Future<Integer> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException(e);
        }
    }
}
