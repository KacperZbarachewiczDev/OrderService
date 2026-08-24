package com.task.ing.orderaudit.application.service;

import com.task.ing.orderaudit.application.port.out.AuditIssuePort;
import com.task.ing.orderaudit.application.port.out.OrderProjectionPort;
import com.task.ing.orderaudit.application.port.out.OrderSourceClient;
import com.task.ing.orderaudit.application.port.out.PaymentSourceClient;
import com.task.ing.orderaudit.application.port.out.SourceUnavailableException;
import com.task.ing.orderaudit.application.config.AuditProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
@Slf4j
@RequiredArgsConstructor
public class AuditCandidateCollector {

    private final OrderSourceClient orderClient;
    private final PaymentSourceClient paymentClient;
    private final OrderProjectionPort orderProjections;
    private final AuditIssuePort auditIssues;
    private final AuditProperties properties;

    public Set<String> collect(Instant windowFrom, Instant windowTo) {
        Set<String> candidates = new LinkedHashSet<>();
        candidates.addAll(fromSource("order-service", () -> orderClient.findOrderIdsModifiedSince(windowFrom)));
        candidates.addAll(fromSource("payment-service", () -> paymentClient.findOrderIdsModifiedSince(windowFrom)));
        candidates.addAll(locallyModified(windowFrom, windowTo));
        candidates.addAll(openIssues());
        log.info("Audit window {} -> {} produced {} candidate orders", windowFrom, windowTo, candidates.size());
        return candidates;
    }

    private List<String> fromSource(String name, SourceLookup lookup) {
        try {
            return lookup.get();
        } catch (SourceUnavailableException e) {
            log.error("Could not list modified orders from {}: {}", name, e.getMessage());
            return List.of();
        }
    }

    private Set<String> locallyModified(Instant windowFrom, Instant windowTo) {
        Set<String> found = new LinkedHashSet<>();
        String after = null;
        List<String> page;
        do {
            page = orderProjections.findOrderIdsUpdatedBetween(
                    windowFrom, windowTo, properties.candidateBatchSize(), after);
            found.addAll(page);
            after = page.isEmpty() ? null : page.getLast();
        } while (page.size() == properties.candidateBatchSize());
        return found;
    }

    private Set<String> openIssues() {
        Set<String> found = new LinkedHashSet<>();
        String after = null;
        List<String> page;
        do {
            page = auditIssues.findOpenOrderIds(properties.candidateBatchSize(), after);
            found.addAll(page);
            after = page.isEmpty() ? null : page.getLast();
        } while (page.size() == properties.candidateBatchSize());
        return found;
    }

    @FunctionalInterface
    private interface SourceLookup {
        List<String> get();
    }
}
