package com.task.ing.orderaudit.application.service;

import com.task.ing.orderaudit.application.port.out.OrderSourceClient;
import com.task.ing.orderaudit.application.port.out.PaymentSourceClient;
import com.task.ing.orderaudit.application.port.out.ResyncJobPort;
import com.task.ing.orderaudit.application.port.out.SourceEvent;
import com.task.ing.orderaudit.application.port.out.SourceUnavailableException;
import com.task.ing.orderaudit.domain.audit.SourceOrderView;
import com.task.ing.orderaudit.domain.resync.ResyncJob;
import com.task.ing.orderaudit.domain.resync.ResyncStatus;
import com.task.ing.orderaudit.support.TestProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static com.task.ing.orderaudit.support.DomainFixtures.ORDER_ID;
import static com.task.ing.orderaudit.support.DomainFixtures.event;
import static com.task.ing.orderaudit.support.DomainFixtures.order;
import static com.task.ing.orderaudit.support.DomainFixtures.sourceWithCounts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ResyncServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-19T12:00:00Z");

    @Mock
    private ResyncJobPort resyncJobs;
    @Mock
    private OrderSourceClient orderClient;
    @Mock
    private PaymentSourceClient paymentClient;
    @Mock
    private LocalStateRebuilder rebuilder;
    @Mock
    private SourceViewLoader sourceViewLoader;
    @Mock
    private OrderAuditProcessor processor;

    private final Executor directExecutor = Runnable::run;

    private ResyncService service() {
        return service(directExecutor);
    }

    private ResyncService service(Executor executor) {
        return new ResyncService(
                resyncJobs, orderClient, paymentClient, rebuilder, sourceViewLoader, processor,
                executor, TestProperties.audit(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void accepts_the_request_and_returns_the_job_that_will_do_the_work() {
        given(resyncJobs.enqueue(ORDER_ID, NOW)).willReturn(job(ResyncStatus.PENDING));
        given(resyncJobs.claim(1L, NOW)).willReturn(Optional.empty());

        ResyncJob accepted = service().requestResync(ORDER_ID);

        assertThat(accepted.orderId()).isEqualTo(ORDER_ID);
        assertThat(accepted.status()).isEqualTo(ResyncStatus.PENDING);
    }

    @Test
    void a_job_someone_else_already_claimed_is_left_alone() {
        given(resyncJobs.claim(1L, NOW)).willReturn(Optional.empty());

        service().execute(1L);

        verify(orderClient, never()).fetchOrder(anyString());
        verify(resyncJobs, never()).succeed(anyLong(), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    @Test
    void rebuilds_from_the_source_and_reports_the_order_as_clean() {
        givenClaimedJob();
        givenSourceData();
        given(sourceViewLoader.loadWithEventDetails(ORDER_ID))
                .willReturn(sourceWithCounts(order(com.task.ing.orderaudit.domain.model.OrderStatus.COMPLETED,
                        "150.00"), null, 1, 0));
        given(processor.verify(eq(ORDER_ID), any(SourceOrderView.class), eq(null), eq(NOW)))
                .willReturn(OrderAuditOutcome.resolved());

        service().execute(1L);

        verify(rebuilder).rebuild(eq(ORDER_ID), any(), any(), any(), any(), eq(NOW));
        verify(resyncJobs).succeed(1L, 0, NOW);
        verify(resyncJobs, never()).fail(anyLong(), anyString(), any());
    }

    @Test
    void reports_how_many_problems_survived_the_rebuild() {
        givenClaimedJob();
        givenSourceData();
        given(sourceViewLoader.loadWithEventDetails(ORDER_ID))
                .willReturn(sourceWithCounts(null, null, 0, 0));
        given(processor.verify(eq(ORDER_ID), any(SourceOrderView.class), eq(null), eq(NOW)))
                .willReturn(OrderAuditOutcome.issue(List.of(
                        com.task.ing.orderaudit.domain.audit.Discrepancy.of(
                                com.task.ing.orderaudit.domain.audit.DiscrepancyType.ORDER_MISSING_IN_SOURCE,
                                "orderId", null, ORDER_ID))));

        service().execute(1L);

        verify(resyncJobs).succeed(1L, 1, NOW);
    }

    @Test
    void marks_the_job_failed_when_a_source_system_is_down() {
        givenClaimedJob();
        willThrow(new SourceUnavailableException("order-service unreachable"))
                .given(orderClient).fetchOrder(ORDER_ID);

        service().execute(1L);

        verify(resyncJobs).fail(eq(1L), org.mockito.ArgumentMatchers.contains("unreachable"), eq(NOW));
        verify(resyncJobs, never()).succeed(anyLong(), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    @Test
    void survives_a_full_executor_queue() {
        given(resyncJobs.enqueue(ORDER_ID, NOW)).willReturn(job(ResyncStatus.PENDING));
        Executor rejecting = command -> {
            throw new RejectedExecutionException("queue full");
        };

        ResyncJob accepted = service(rejecting).requestResync(ORDER_ID);

        assertThat(accepted.status()).isEqualTo(ResyncStatus.PENDING);
        verify(resyncJobs, never()).fail(anyLong(), anyString(), any());
    }

    @Test
    void picks_up_jobs_that_were_accepted_but_never_ran() {
        given(resyncJobs.findStalePendingJobIds(NOW.minus(Duration.ofMinutes(2)), 50))
                .willReturn(List.of(7L, 8L));
        given(resyncJobs.claim(anyLong(), eq(NOW))).willReturn(Optional.empty());

        assertThat(service().recoverStaleJobs()).isEqualTo(2);
        verify(resyncJobs).claim(7L, NOW);
        verify(resyncJobs).claim(8L, NOW);
    }

    @Test
    void reports_no_recovery_work_when_nothing_is_stuck() {
        given(resyncJobs.findStalePendingJobIds(any(), org.mockito.ArgumentMatchers.anyInt()))
                .willReturn(List.of());

        assertThat(service().recoverStaleJobs()).isZero();
    }

    @Test
    void exposes_the_latest_job_for_an_order() {
        given(resyncJobs.findLatestForOrder(ORDER_ID)).willReturn(Optional.of(job(ResyncStatus.SUCCEEDED)));

        assertThat(service().resyncStatus(ORDER_ID))
                .get()
                .extracting(ResyncJob::status)
                .isEqualTo(ResyncStatus.SUCCEEDED);
    }

    private void givenClaimedJob() {
        given(resyncJobs.claim(1L, NOW)).willReturn(Optional.of(job(ResyncStatus.RUNNING)));
    }

    private void givenSourceData() {
        given(orderClient.fetchOrder(ORDER_ID)).willReturn(Optional.empty());
        given(orderClient.fetchEvents(ORDER_ID)).willReturn(List.of(
                new SourceEvent(event("EVT-1", "ORDER_CREATED"), ORDER_ID, ORDER_ID, "{}")));
        given(paymentClient.fetchPayment(ORDER_ID)).willReturn(Optional.empty());
        given(paymentClient.fetchEvents(ORDER_ID)).willReturn(List.of());
    }

    private ResyncJob job(ResyncStatus status) {
        return new ResyncJob(1L, ORDER_ID, status, NOW, null, null, 1, null, null);
    }
}
