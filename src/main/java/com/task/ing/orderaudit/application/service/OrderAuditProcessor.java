package com.task.ing.orderaudit.application.service;

import com.task.ing.orderaudit.application.port.out.AuditIssuePort;
import com.task.ing.orderaudit.application.port.out.SourceUnavailableException;
import com.task.ing.orderaudit.domain.audit.AuditEngine;
import com.task.ing.orderaudit.domain.audit.Discrepancy;
import com.task.ing.orderaudit.domain.audit.LocalOrderView;
import com.task.ing.orderaudit.domain.audit.SourceOrderView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class OrderAuditProcessor {

    private final LocalViewLoader localViewLoader;
    private final SourceViewLoader sourceViewLoader;
    private final AuditEngine auditEngine;
    private final AuditIssuePort auditIssues;

    public OrderAuditOutcome audit(String orderId, Long auditRunId, Instant now) {
        LocalOrderView local = localViewLoader.load(orderId);
        SourceOrderView source;
        try {
            source = sourceViewLoader.load(orderId, local);
        } catch (SourceUnavailableException e) {
            log.warn("Order {} left inconclusive: {}", orderId, e.getMessage());
            return OrderAuditOutcome.inconclusive(e.getMessage());
        }
        return record(orderId, auditEngine.compare(local, source), auditRunId, now);
    }

    public OrderAuditOutcome verify(String orderId, SourceOrderView source, Long auditRunId, Instant now) {
        LocalOrderView local = localViewLoader.load(orderId);
        return record(orderId, auditEngine.compare(local, source), auditRunId, now);
    }

    private OrderAuditOutcome record(
            String orderId, List<Discrepancy> discrepancies, Long auditRunId, Instant now) {
        if (discrepancies.isEmpty()) {
            boolean wasOpen = auditIssues.resolve(orderId, now);
            return wasOpen ? OrderAuditOutcome.resolved() : OrderAuditOutcome.clean();
        }
        auditIssues.record(orderId, discrepancies, auditRunId, now);
        return OrderAuditOutcome.issue(discrepancies);
    }
}
