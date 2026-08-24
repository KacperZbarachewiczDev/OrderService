package com.task.ing.orderaudit.application.service;

import com.task.ing.orderaudit.application.port.in.ResyncOrderUseCase;
import com.task.ing.orderaudit.application.port.out.OrderSourceClient;
import com.task.ing.orderaudit.application.port.out.PaymentSourceClient;
import com.task.ing.orderaudit.application.port.out.ResyncJobPort;
import com.task.ing.orderaudit.application.port.out.SourceEvent;
import com.task.ing.orderaudit.application.config.AuditProperties;
import com.task.ing.orderaudit.domain.audit.SourceOrderView;
import com.task.ing.orderaudit.domain.model.OrderSnapshot;
import com.task.ing.orderaudit.domain.model.PaymentSnapshot;
import com.task.ing.orderaudit.domain.resync.ResyncJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@Service
@Slf4j
@RequiredArgsConstructor
public class ResyncService implements ResyncOrderUseCase {

    private final ResyncJobPort resyncJobs;
    private final OrderSourceClient orderClient;
    private final PaymentSourceClient paymentClient;
    private final LocalStateRebuilder rebuilder;
    private final SourceViewLoader sourceViewLoader;
    private final OrderAuditProcessor processor;
    @Qualifier("resyncExecutor")
    private final Executor resyncExecutor;
    private final AuditProperties properties;
    private final Clock clock;

    @Override
    public ResyncJob requestResync(String orderId) {
        ResyncJob job = resyncJobs.enqueue(orderId, clock.instant());
        dispatch(job.id());
        return job;
    }

    @Override
    public Optional<ResyncJob> resyncStatus(String orderId) {
        return resyncJobs.findLatestForOrder(orderId);
    }

    @Override
    public int recoverStaleJobs() {
        Instant threshold = clock.instant().minus(properties.resync().staleAfter());
        List<Long> stale = resyncJobs.findStalePendingJobIds(threshold, properties.resync().pollLimit());
        stale.forEach(this::dispatch);
        if (!stale.isEmpty()) {
            log.info("Re-dispatched {} stale resync job(s)", stale.size());
        }
        return stale.size();
    }

    private void dispatch(long jobId) {
        try {
            resyncExecutor.execute(() -> execute(jobId));
        } catch (RejectedExecutionException e) {
            log.warn("Resync job {} could not be dispatched right now: {}", jobId, e.getMessage());
        }
    }

    public void execute(long jobId) {
        Optional<ResyncJob> claimed = resyncJobs.claim(jobId, clock.instant());
        if (claimed.isEmpty()) {
            log.debug("Resync job {} was already claimed elsewhere", jobId);
            return;
        }
        String orderId = claimed.get().orderId();
        try {
            Optional<OrderSnapshot> order = orderClient.fetchOrder(orderId);
            List<SourceEvent> orderEvents = orderClient.fetchEvents(orderId);
            Optional<PaymentSnapshot> payment = paymentClient.fetchPayment(orderId);
            List<SourceEvent> paymentEvents = paymentClient.fetchEvents(orderId);

            rebuilder.rebuild(orderId, order, orderEvents, payment, paymentEvents, clock.instant());

            SourceOrderView source = sourceViewLoader.loadWithEventDetails(orderId);
            OrderAuditOutcome outcome = processor.verify(orderId, source, null, clock.instant());

            resyncJobs.succeed(jobId, outcome.discrepancies().size(), clock.instant());
            log.info("Resync of order {} finished with {} remaining discrepancy(ies)",
                    orderId, outcome.discrepancies().size());
        } catch (RuntimeException e) {
            log.error("Resync of order {} failed", orderId, e);
            resyncJobs.fail(jobId, e.toString(), clock.instant());
        }
    }
}
