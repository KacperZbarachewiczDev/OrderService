package com.task.ing.orderaudit.application.service;

import com.task.ing.orderaudit.application.port.out.AuditIssuePort;
import com.task.ing.orderaudit.application.port.out.OrderProjectionPort;
import com.task.ing.orderaudit.application.port.out.OrderSourceClient;
import com.task.ing.orderaudit.application.port.out.PaymentSourceClient;
import com.task.ing.orderaudit.application.port.out.SourceUnavailableException;
import com.task.ing.orderaudit.support.TestProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;

@ExtendWith(MockitoExtension.class)
class AuditCandidateCollectorTest {

    private static final Instant FROM = Instant.parse("2026-08-18T02:00:00Z");
    private static final Instant TO = Instant.parse("2026-08-19T02:00:00Z");

    @Mock
    private OrderSourceClient orderClient;
    @Mock
    private PaymentSourceClient paymentClient;
    @Mock
    private OrderProjectionPort orderProjections;
    @Mock
    private AuditIssuePort auditIssues;

    private AuditCandidateCollector collector() {
        return new AuditCandidateCollector(
                orderClient, paymentClient, orderProjections, auditIssues, TestProperties.audit());
    }

    @Test
    void merges_all_four_feeds_and_removes_duplicates() {
        given(orderClient.findOrderIdsModifiedSince(FROM)).willReturn(List.of("ORD-1", "ORD-2"));
        given(paymentClient.findOrderIdsModifiedSince(FROM)).willReturn(List.of("ORD-2", "ORD-3"));
        given(orderProjections.findOrderIdsUpdatedBetween(FROM, TO, 500, null)).willReturn(List.of("ORD-4"));
        given(auditIssues.findOpenOrderIds(500, null)).willReturn(List.of("ORD-1", "ORD-5"));

        Set<String> candidates = collector().collect(FROM, TO);

        assertThat(candidates).containsExactlyInAnyOrder("ORD-1", "ORD-2", "ORD-3", "ORD-4", "ORD-5");
    }

    @Test
    void always_re_checks_orders_that_are_currently_flagged() {
        given(orderClient.findOrderIdsModifiedSince(FROM)).willReturn(List.of());
        given(paymentClient.findOrderIdsModifiedSince(FROM)).willReturn(List.of());
        given(orderProjections.findOrderIdsUpdatedBetween(FROM, TO, 500, null)).willReturn(List.of());
        given(auditIssues.findOpenOrderIds(500, null)).willReturn(List.of("ORD-9"));

        assertThat(collector().collect(FROM, TO)).containsExactly("ORD-9");
    }

    @Test
    void keeps_going_when_one_source_cannot_be_listed() {
        willThrow(new SourceUnavailableException("order-service down"))
                .given(orderClient).findOrderIdsModifiedSince(FROM);
        given(paymentClient.findOrderIdsModifiedSince(FROM)).willReturn(List.of("ORD-3"));
        given(orderProjections.findOrderIdsUpdatedBetween(FROM, TO, 500, null)).willReturn(List.of());
        given(auditIssues.findOpenOrderIds(500, null)).willReturn(List.of());

        assertThat(collector().collect(FROM, TO)).containsExactly("ORD-3");
    }

    @Test
    void pages_through_local_orders_until_the_source_is_exhausted() {
        given(orderClient.findOrderIdsModifiedSince(FROM)).willReturn(List.of());
        given(paymentClient.findOrderIdsModifiedSince(FROM)).willReturn(List.of());
        given(orderProjections.findOrderIdsUpdatedBetween(eq(FROM), eq(TO), eq(500), any()))
                .willReturn(fullPage("A"), List.of("B-1"));
        given(auditIssues.findOpenOrderIds(anyInt(), any())).willReturn(List.of());

        Set<String> candidates = collector().collect(FROM, TO);

        assertThat(candidates).hasSize(501).contains("A-0", "A-499", "B-1");
    }

    @Test
    void pages_through_open_issues_until_they_are_exhausted() {
        given(orderClient.findOrderIdsModifiedSince(FROM)).willReturn(List.of());
        given(paymentClient.findOrderIdsModifiedSince(FROM)).willReturn(List.of());
        given(orderProjections.findOrderIdsUpdatedBetween(eq(FROM), eq(TO), eq(500), any()))
                .willReturn(List.of());
        given(auditIssues.findOpenOrderIds(eq(500), any())).willReturn(fullPage("I"), List.of("J-1"));

        assertThat(collector().collect(FROM, TO)).hasSize(501).contains("I-0", "J-1");
    }

    private List<String> fullPage(String prefix) {
        return java.util.stream.IntStream.range(0, 500).mapToObj(i -> prefix + "-" + i).toList();
    }
}
