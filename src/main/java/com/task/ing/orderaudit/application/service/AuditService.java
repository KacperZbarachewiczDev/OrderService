package com.task.ing.orderaudit.application.service;

import com.task.ing.orderaudit.application.port.in.RunAuditUseCase;
import com.task.ing.orderaudit.application.port.out.AuditRunPort;
import com.task.ing.orderaudit.application.port.out.NotificationOutboxPort;
import com.task.ing.orderaudit.application.port.out.SchedulerLockPort;
import com.task.ing.orderaudit.application.config.AuditProperties;
import com.task.ing.orderaudit.domain.audit.AuditRun;
import com.task.ing.orderaudit.domain.audit.AuditRunStats;
import com.task.ing.orderaudit.domain.audit.AuditTrigger;
import com.task.ing.orderaudit.domain.audit.Discrepancy;
import com.task.ing.orderaudit.domain.audit.DiscrepancyType;
import com.task.ing.orderaudit.domain.notification.AuditNotificationComposer;
import com.task.ing.orderaudit.domain.notification.IssueDigest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class AuditService implements RunAuditUseCase {

    private final AuditRunPort auditRuns;
    private final AuditCandidateCollector candidateCollector;
    private final OrderAuditProcessor processor;
    private final NotificationOutboxPort outbox;
    private final AuditNotificationComposer composer;
    private final SchedulerLockPort schedulerLock;
    private final AuditProperties properties;
    private final Clock clock;
    private final String nodeId = UUID.randomUUID().toString();

    @Override
    public Optional<AuditRun> run(AuditTrigger trigger) {
        Instant now = clock.instant();
        if (!schedulerLock.acquire(properties.lockName(), nodeId, now, properties.lockLease())) {
            log.info("Audit skipped: another node holds the '{}' lock", properties.lockName());
            return Optional.empty();
        }
        try {
            return Optional.of(execute(trigger, now));
        } finally {
            schedulerLock.release(properties.lockName(), nodeId, clock.instant());
        }
    }

    private AuditRun execute(AuditTrigger trigger, Instant startedAt) {
        Instant windowFrom = auditRuns.lastCompletedWindowEnd()
                .orElseGet(() -> startedAt.minus(properties.initialLookback()));
        AuditRun run = auditRuns.start(trigger, windowFrom, startedAt, startedAt);
        log.info("Audit run #{} started ({}), window {} -> {}", run.id(), trigger, windowFrom, startedAt);

        AuditRunStats stats = AuditRunStats.empty();
        List<IssueDigest> digests = new ArrayList<>();
        try {
            Set<String> candidates = candidateCollector.collect(windowFrom, startedAt);
            for (String orderId : candidates) {
                stats = auditOne(orderId, run.id(), stats, digests);
            }
        } catch (RuntimeException e) {
            log.error("Audit run #{} failed", run.id(), e);
            auditRuns.fail(run.id(), e.toString(), clock.instant());
            throw e;
        }

        AuditRun finished = auditRuns.complete(run.id(), stats, clock.instant());
        log.info("Audit run #{} finished: {}", run.id(), stats);
        if (stats.hasFindings()) {
            notify(finished, digests);
        }
        return finished;
    }

    private AuditRunStats auditOne(
            String orderId, Long runId, AuditRunStats stats, List<IssueDigest> digests) {
        OrderAuditOutcome outcome;
        try {
            outcome = processor.audit(orderId, runId, clock.instant());
        } catch (RuntimeException e) {
            log.error("Audit of order {} failed unexpectedly", orderId, e);
            return stats.withInconclusive();
        }
        return switch (outcome.kind()) {
            case CLEAN -> stats.withChecked();
            case RESOLVED -> stats.withResolved();
            case INCONCLUSIVE -> stats.withInconclusive();
            case ISSUE -> {
                collectDigest(orderId, outcome.discrepancies(), digests);
                yield stats.withIssue(outcome.discrepancies().size());
            }
        };
    }

    private void collectDigest(String orderId, List<Discrepancy> discrepancies, List<IssueDigest> digests) {
        if (digests.size() >= properties.notification().maxCollectedDigests()) {
            return;
        }
        Set<String> types = new LinkedHashSet<>();
        discrepancies.stream().map(Discrepancy::type).map(DiscrepancyType::name).forEach(types::add);
        digests.add(new IssueDigest(
                orderId,
                Discrepancy.highestSeverity(discrepancies),
                discrepancies.size(),
                List.copyOf(types)));
    }

    private void notify(AuditRun run, List<IssueDigest> digests) {
        List<String> recipients = properties.notification().recipients();
        if (recipients.isEmpty()) {
            log.warn("Audit run #{} found issues but no notification recipients are configured", run.id());
            return;
        }
        outbox.enqueue(composer.compose(recipients, run, digests), run.id(), clock.instant());
    }
}
