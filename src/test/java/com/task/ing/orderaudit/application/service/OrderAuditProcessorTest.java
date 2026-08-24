package com.task.ing.orderaudit.application.service;

import com.task.ing.orderaudit.application.port.out.AuditIssuePort;
import com.task.ing.orderaudit.application.port.out.SourceUnavailableException;
import com.task.ing.orderaudit.domain.audit.AuditEngine;
import com.task.ing.orderaudit.domain.audit.LocalOrderView;
import com.task.ing.orderaudit.domain.audit.SourceOrderView;
import com.task.ing.orderaudit.domain.model.OrderStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static com.task.ing.orderaudit.support.DomainFixtures.ORDER_ID;
import static com.task.ing.orderaudit.support.DomainFixtures.completedHistory;
import static com.task.ing.orderaudit.support.DomainFixtures.local;
import static com.task.ing.orderaudit.support.DomainFixtures.order;
import static com.task.ing.orderaudit.support.DomainFixtures.sourceWithCounts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OrderAuditProcessorTest {

    private static final Instant NOW = Instant.parse("2026-08-19T12:00:00Z");

    @Mock
    private LocalViewLoader localViewLoader;
    @Mock
    private SourceViewLoader sourceViewLoader;
    @Mock
    private AuditIssuePort auditIssues;

    private OrderAuditProcessor processor() {
        return new OrderAuditProcessor(localViewLoader, sourceViewLoader, new AuditEngine(), auditIssues);
    }

    @Test
    void an_order_that_was_already_fine_stays_quiet() {
        givenMatchingSides();
        given(auditIssues.resolve(ORDER_ID, NOW)).willReturn(false);

        OrderAuditOutcome outcome = processor().audit(ORDER_ID, 1L, NOW);

        assertThat(outcome.kind()).isEqualTo(OrderAuditOutcome.Kind.CLEAN);
        verify(auditIssues, never()).record(anyString(), anyList(), any(), any());
    }

    @Test
    void an_order_that_became_consistent_closes_its_issue() {
        givenMatchingSides();
        given(auditIssues.resolve(ORDER_ID, NOW)).willReturn(true);

        assertThat(processor().audit(ORDER_ID, 1L, NOW).kind())
                .isEqualTo(OrderAuditOutcome.Kind.RESOLVED);
    }

    @Test
    void an_inconsistent_order_is_recorded_with_its_findings() {
        LocalOrderView localView = local(null, null, List.of(), List.of());
        given(localViewLoader.load(ORDER_ID)).willReturn(localView);
        given(sourceViewLoader.load(ORDER_ID, localView))
                .willReturn(sourceWithCounts(order(OrderStatus.COMPLETED, "150.00"), null, 0, 0));

        OrderAuditOutcome outcome = processor().audit(ORDER_ID, 7L, NOW);

        assertThat(outcome.kind()).isEqualTo(OrderAuditOutcome.Kind.ISSUE);
        assertThat(outcome.discrepancies()).hasSize(1);
        verify(auditIssues).record(eq(ORDER_ID), anyList(), eq(7L), eq(NOW));
        verify(auditIssues, never()).resolve(anyString(), any());
    }

    @Test
    void an_unreachable_source_leaves_the_order_inconclusive_and_records_nothing() {
        LocalOrderView localView = local(order(OrderStatus.COMPLETED, "150.00"), null, completedHistory(), List.of());
        given(localViewLoader.load(ORDER_ID)).willReturn(localView);
        willThrow(new SourceUnavailableException("order-service timed out"))
                .given(sourceViewLoader).load(ORDER_ID, localView);

        OrderAuditOutcome outcome = processor().audit(ORDER_ID, 1L, NOW);

        assertThat(outcome.kind()).isEqualTo(OrderAuditOutcome.Kind.INCONCLUSIVE);
        assertThat(outcome.reason()).contains("timed out");
        verify(auditIssues, never()).record(anyString(), anyList(), any(), any());
        verify(auditIssues, never()).resolve(anyString(), any());
    }

    @Test
    void re_verification_after_a_resync_uses_the_data_it_is_handed() {
        LocalOrderView localView = local(order(OrderStatus.COMPLETED, "150.00"), null, completedHistory(), List.of());
        given(localViewLoader.load(ORDER_ID)).willReturn(localView);
        SourceOrderView source = sourceWithCounts(order(OrderStatus.COMPLETED, "150.00"), null, 2, 0);
        given(auditIssues.resolve(ORDER_ID, NOW)).willReturn(true);

        OrderAuditOutcome outcome = processor().verify(ORDER_ID, source, null, NOW);

        assertThat(outcome.kind()).isEqualTo(OrderAuditOutcome.Kind.RESOLVED);
        verify(sourceViewLoader, never()).load(anyString(), any());
    }

    private void givenMatchingSides() {
        LocalOrderView localView = local(order(OrderStatus.COMPLETED, "150.00"), null, completedHistory(), List.of());
        given(localViewLoader.load(ORDER_ID)).willReturn(localView);
        given(sourceViewLoader.load(ORDER_ID, localView))
                .willReturn(sourceWithCounts(order(OrderStatus.COMPLETED, "150.00"), null, 2, 0));
    }
}
