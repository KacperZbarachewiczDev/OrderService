package com.task.ing.orderaudit.application.service;

import com.task.ing.orderaudit.application.port.out.AuditRunPort;
import com.task.ing.orderaudit.application.port.out.NotificationOutboxPort;
import com.task.ing.orderaudit.application.port.out.SchedulerLockPort;
import com.task.ing.orderaudit.domain.audit.AuditRun;
import com.task.ing.orderaudit.domain.audit.AuditRunStats;
import com.task.ing.orderaudit.domain.audit.AuditRunStatus;
import com.task.ing.orderaudit.domain.audit.AuditTrigger;
import com.task.ing.orderaudit.domain.audit.Discrepancy;
import com.task.ing.orderaudit.domain.audit.DiscrepancyType;
import com.task.ing.orderaudit.domain.notification.AuditNotificationComposer;
import com.task.ing.orderaudit.domain.notification.NotificationMessage;
import com.task.ing.orderaudit.support.TestProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-19T02:00:00Z");

    @Mock
    private AuditRunPort auditRuns;
    @Mock
    private AuditCandidateCollector candidateCollector;
    @Mock
    private OrderAuditProcessor processor;
    @Mock
    private NotificationOutboxPort outbox;
    @Mock
    private SchedulerLockPort schedulerLock;
    @Captor
    private ArgumentCaptor<AuditRunStats> statsCaptor;
    @Captor
    private ArgumentCaptor<NotificationMessage> messageCaptor;

    private AuditService service() {
        return service(TestProperties.audit());
    }

    private AuditService service(com.task.ing.orderaudit.application.config.AuditProperties properties) {
        return new AuditService(
                auditRuns, candidateCollector, processor, outbox,
                new AuditNotificationComposer(
                        properties.notification().maxListedIssues(), properties.notification().issuesUrl()),
                schedulerLock, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void stands_down_when_another_node_holds_the_lock() {
        given(schedulerLock.acquire(eq("daily-audit"), anyString(), eq(NOW), eq(Duration.ofHours(2))))
                .willReturn(false);

        assertThat(service().run(AuditTrigger.SCHEDULED)).isEmpty();
        verify(auditRuns, never()).start(any(), any(), any(), any());
        verify(schedulerLock, never()).release(anyString(), anyString(), any());
    }

    @Test
    void releases_the_lock_even_when_the_run_blows_up() {
        givenLockAcquired();
        given(auditRuns.lastCompletedWindowEnd()).willReturn(Optional.empty());
        given(auditRuns.start(any(), any(), any(), any())).willReturn(runningRun());
        willThrow(new IllegalStateException("boom")).given(candidateCollector).collect(any(), any());

        assertThatThrownBy(() -> service().run(AuditTrigger.SCHEDULED))
                .isInstanceOf(IllegalStateException.class);

        verify(auditRuns).fail(eq(1L), anyString(), eq(NOW));
        verify(schedulerLock).release(eq("daily-audit"), anyString(), eq(NOW));
    }

    @Test
    void looks_back_a_configured_window_when_no_run_has_ever_completed() {
        givenLockAcquired();
        given(auditRuns.lastCompletedWindowEnd()).willReturn(Optional.empty());
        given(auditRuns.start(any(), any(), any(), any())).willReturn(runningRun());
        given(candidateCollector.collect(any(), any())).willReturn(Set.of());
        given(auditRuns.complete(anyLong(), any(), any())).willReturn(completedRun(AuditRunStats.empty()));

        service().run(AuditTrigger.SCHEDULED);

        verify(auditRuns).start(
                eq(AuditTrigger.SCHEDULED), eq(NOW.minus(Duration.ofDays(30))), eq(NOW), eq(NOW));
    }

    @Test
    void continues_from_where_the_last_successful_run_stopped() {
        Instant watermark = NOW.minus(Duration.ofDays(1));
        givenLockAcquired();
        given(auditRuns.lastCompletedWindowEnd()).willReturn(Optional.of(watermark));
        given(auditRuns.start(any(), any(), any(), any())).willReturn(runningRun());
        given(candidateCollector.collect(watermark, NOW)).willReturn(Set.of());
        given(auditRuns.complete(anyLong(), any(), any())).willReturn(completedRun(AuditRunStats.empty()));

        service().run(AuditTrigger.SCHEDULED);

        verify(auditRuns).start(eq(AuditTrigger.SCHEDULED), eq(watermark), eq(NOW), eq(NOW));
    }

    @Test
    void tallies_every_kind_of_outcome() {
        givenLockAcquired();
        givenRunStarted(orderedSet("ORD-1", "ORD-2", "ORD-3", "ORD-4"));
        given(processor.audit(eq("ORD-1"), eq(1L), any())).willReturn(OrderAuditOutcome.clean());
        given(processor.audit(eq("ORD-2"), eq(1L), any())).willReturn(OrderAuditOutcome.resolved());
        given(processor.audit(eq("ORD-3"), eq(1L), any())).willReturn(OrderAuditOutcome.issue(List.of(
                Discrepancy.of(DiscrepancyType.ORDER_STATUS_MISMATCH, "status", "PAID", "CREATED"),
                Discrepancy.of(DiscrepancyType.ORDER_AMOUNT_MISMATCH, "totalAmount", "1", "2"))));
        given(processor.audit(eq("ORD-4"), eq(1L), any())).willReturn(OrderAuditOutcome.inconclusive("down"));
        given(auditRuns.complete(anyLong(), any(), any())).willReturn(completedRun(new AuditRunStats(2, 1, 1, 2, 1)));

        service().run(AuditTrigger.SCHEDULED);

        verify(auditRuns).complete(eq(1L), statsCaptor.capture(), eq(NOW));
        AuditRunStats stats = statsCaptor.getValue();
        assertThat(stats.ordersChecked()).isEqualTo(3);
        assertThat(stats.ordersWithIssues()).isEqualTo(1);
        assertThat(stats.ordersResolved()).isEqualTo(1);
        assertThat(stats.discrepanciesFound()).isEqualTo(2);
        assertThat(stats.ordersInconclusive()).isEqualTo(1);
    }

    @Test
    void an_unexpected_failure_on_one_order_does_not_abort_the_run() {
        givenLockAcquired();
        givenRunStarted(orderedSet("ORD-1", "ORD-2"));
        willThrow(new IllegalStateException("bad row")).given(processor).audit(eq("ORD-1"), eq(1L), any());
        given(processor.audit(eq("ORD-2"), eq(1L), any())).willReturn(OrderAuditOutcome.clean());
        given(auditRuns.complete(anyLong(), any(), any())).willReturn(completedRun(new AuditRunStats(1, 0, 0, 0, 1)));

        service().run(AuditTrigger.SCHEDULED);

        verify(auditRuns).complete(eq(1L), statsCaptor.capture(), eq(NOW));
        assertThat(statsCaptor.getValue().ordersInconclusive()).isEqualTo(1);
        assertThat(statsCaptor.getValue().ordersChecked()).isEqualTo(1);
    }

    @Test
    void hands_the_finished_run_back_to_the_caller() {
        givenLockAcquired();
        givenRunStarted(orderedSet());
        AuditRun completed = completedRun(new AuditRunStats(0, 0, 0, 0, 0));
        given(auditRuns.complete(anyLong(), any(), any())).willReturn(completed);

        assertThat(service().run(AuditTrigger.SCHEDULED)).contains(completed);
        verify(schedulerLock).release(eq("daily-audit"), anyString(), eq(NOW));
    }

    @Test
    void queues_a_notification_only_when_something_is_actually_wrong() {
        givenLockAcquired();
        givenRunStarted(orderedSet("ORD-1"));
        given(processor.audit(eq("ORD-1"), eq(1L), any())).willReturn(OrderAuditOutcome.clean());
        given(auditRuns.complete(anyLong(), any(), any())).willReturn(completedRun(new AuditRunStats(1, 0, 0, 0, 0)));

        service().run(AuditTrigger.SCHEDULED);

        verify(outbox, never()).enqueue(any(), any(), any());
    }

    @Test
    void queues_a_notification_naming_the_broken_orders() {
        givenLockAcquired();
        givenRunStarted(orderedSet("ORD-1"));
        given(processor.audit(eq("ORD-1"), eq(1L), any())).willReturn(OrderAuditOutcome.issue(List.of(
                Discrepancy.of(DiscrepancyType.ORDER_STATUS_MISMATCH, "status", "PAID", "CREATED"))));
        given(auditRuns.complete(anyLong(), any(), any())).willReturn(completedRun(new AuditRunStats(1, 1, 0, 1, 0)));

        service().run(AuditTrigger.SCHEDULED);

        verify(outbox).enqueue(messageCaptor.capture(), eq(1L), eq(NOW));
        assertThat(messageCaptor.getValue().subject()).contains("1 order with issues");
        assertThat(messageCaptor.getValue().body()).contains("ORD-1").contains("ORDER_STATUS_MISMATCH");
        assertThat(messageCaptor.getValue().recipients()).containsExactly("ops@example.com");
    }

    @Test
    void does_not_try_to_send_anything_when_no_recipient_is_configured() {
        givenLockAcquired();
        givenRunStarted(orderedSet("ORD-1"));
        given(processor.audit(eq("ORD-1"), eq(1L), any())).willReturn(OrderAuditOutcome.issue(List.of(
                Discrepancy.of(DiscrepancyType.ORDER_STATUS_MISMATCH, "status", "PAID", "CREATED"))));
        given(auditRuns.complete(anyLong(), any(), any())).willReturn(completedRun(new AuditRunStats(1, 1, 0, 1, 0)));

        service(TestProperties.withoutRecipients()).run(AuditTrigger.SCHEDULED);

        verify(outbox, never()).enqueue(any(), any(), any());
    }

    @Test
    void stops_collecting_per_order_detail_once_the_cap_is_reached() {
        givenLockAcquired();
        Set<String> orders = orderedSet("ORD-1", "ORD-2", "ORD-3");
        givenRunStarted(orders);
        orders.forEach(orderId -> given(processor.audit(eq(orderId), eq(1L), any()))
                .willReturn(OrderAuditOutcome.issue(List.of(
                        Discrepancy.of(DiscrepancyType.ORDER_STATUS_MISMATCH, "status", "PAID", "CREATED")))));
        given(auditRuns.complete(anyLong(), any(), any())).willReturn(completedRun(new AuditRunStats(3, 3, 0, 3, 0)));

        service(TestProperties.audit(25, 2)).run(AuditTrigger.SCHEDULED);

        verify(outbox).enqueue(messageCaptor.capture(), eq(1L), eq(NOW));
        assertThat(messageCaptor.getValue().body())
                .contains("ORD-1")
                .contains("ORD-2")
                .doesNotContain("ORD-3")
                .contains("... and 1 more");
    }

    private void givenLockAcquired() {
        given(schedulerLock.acquire(eq("daily-audit"), anyString(), eq(NOW), eq(Duration.ofHours(2))))
                .willReturn(true);
    }

    private void givenRunStarted(Set<String> candidates) {
        given(auditRuns.lastCompletedWindowEnd()).willReturn(Optional.empty());
        given(auditRuns.start(any(), any(), any(), any())).willReturn(runningRun());
        given(candidateCollector.collect(any(), any())).willReturn(candidates);
    }

    private Set<String> orderedSet(String... orderIds) {
        return new LinkedHashSet<>(List.of(orderIds));
    }

    private AuditRun runningRun() {
        return new AuditRun(1L, AuditTrigger.SCHEDULED, AuditRunStatus.RUNNING,
                NOW.minus(Duration.ofDays(1)), NOW, NOW, null, AuditRunStats.empty(), null);
    }

    private AuditRun completedRun(AuditRunStats stats) {
        return new AuditRun(1L, AuditTrigger.SCHEDULED, AuditRunStatus.COMPLETED,
                NOW.minus(Duration.ofDays(1)), NOW, NOW, NOW, stats, null);
    }
}
