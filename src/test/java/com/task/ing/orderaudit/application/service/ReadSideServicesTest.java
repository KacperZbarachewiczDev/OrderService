package com.task.ing.orderaudit.application.service;

import com.task.ing.orderaudit.application.port.out.AuditIssuePort;
import com.task.ing.orderaudit.application.port.out.AuditRunPort;
import com.task.ing.orderaudit.application.port.out.EventStorePort;
import com.task.ing.orderaudit.application.port.out.OrderProjectionPort;
import com.task.ing.orderaudit.application.port.out.PageResult;
import com.task.ing.orderaudit.application.port.out.Pagination;
import com.task.ing.orderaudit.application.port.out.PaymentProjectionPort;
import com.task.ing.orderaudit.domain.audit.AuditIssue;
import com.task.ing.orderaudit.domain.audit.AuditIssueDetail;
import com.task.ing.orderaudit.domain.audit.AuditRun;
import com.task.ing.orderaudit.domain.audit.IssueStatus;
import com.task.ing.orderaudit.domain.audit.LocalOrderView;
import com.task.ing.orderaudit.domain.audit.Severity;
import com.task.ing.orderaudit.domain.model.EventSource;
import com.task.ing.orderaudit.domain.model.OrderProjection;
import com.task.ing.orderaudit.domain.model.OrderStatus;
import com.task.ing.orderaudit.domain.model.PaymentProjection;
import com.task.ing.orderaudit.domain.model.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.task.ing.orderaudit.support.DomainFixtures.ORDER_ID;
import static com.task.ing.orderaudit.support.DomainFixtures.event;
import static com.task.ing.orderaudit.support.DomainFixtures.order;
import static com.task.ing.orderaudit.support.DomainFixtures.payment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class ReadSideServicesTest {

    private static final Instant NOW = Instant.parse("2026-08-19T12:00:00Z");

    @Mock
    private AuditIssuePort auditIssues;
    @Mock
    private AuditRunPort auditRuns;
    @Mock
    private OrderProjectionPort orderProjections;
    @Mock
    private PaymentProjectionPort paymentProjections;
    @Mock
    private EventStorePort eventStore;

    @Test
    void the_issue_list_passes_the_filters_through_and_returns_what_the_store_holds() {
        PageResult<AuditIssue> page = new PageResult<>(List.of(issue()), 0, 20, 1);
        given(auditIssues.findAll(IssueStatus.OPEN, Severity.CRITICAL, Pagination.of(0, 20)))
                .willReturn(page);

        PageResult<AuditIssue> result = new AuditQueryService(auditIssues, auditRuns)
                .listIssues(IssueStatus.OPEN, Severity.CRITICAL, Pagination.of(0, 20));

        assertThat(result).isSameAs(page);
        assertThat(result.totalPages()).isEqualTo(1);
    }

    @Test
    void the_detail_view_returns_what_the_store_holds() {
        AuditIssueDetail detail = new AuditIssueDetail(issue(), List.of());
        given(auditIssues.findDetail(ORDER_ID)).willReturn(Optional.of(detail));

        assertThat(new AuditQueryService(auditIssues, auditRuns).issueDetail(ORDER_ID))
                .contains(detail);
    }

    @Test
    void the_run_history_returns_what_the_store_holds() {
        PageResult<AuditRun> page = new PageResult<>(List.of(), 0, 20, 0);
        given(auditRuns.findAll(Pagination.of(0, 20))).willReturn(page);

        assertThat(new AuditQueryService(auditIssues, auditRuns).listRuns(Pagination.of(0, 20)))
                .isSameAs(page);
    }

    @Test
    void the_local_view_gathers_the_order_the_payment_and_both_histories() {
        given(orderProjections.find(ORDER_ID)).willReturn(Optional.of(
                new OrderProjection(order(OrderStatus.COMPLETED, "150.00"), null)));
        given(paymentProjections.findByOrderId(ORDER_ID)).willReturn(Optional.of(
                new PaymentProjection(payment(PaymentStatus.PAID, "150.00"), null)));
        given(eventStore.findEventRefs(ORDER_ID, EventSource.ORDER))
                .willReturn(List.of(event("EVT-1", "ORDER_CREATED")));
        given(eventStore.findEventRefs(ORDER_ID, EventSource.PAYMENT))
                .willReturn(List.of(event("PAY-EVT-1", "PAYMENT_COMPLETED")));

        LocalOrderView view = new LocalViewLoader(orderProjections, paymentProjections, eventStore)
                .load(ORDER_ID);

        assertThat(view.orderId()).isEqualTo(ORDER_ID);
        assertThat(view.order()).isPresent();
        assertThat(view.payment()).isPresent();
        assertThat(view.orderEvents()).hasSize(1);
        assertThat(view.paymentEvents()).hasSize(1);
    }

    @Test
    void the_local_view_of_an_unknown_order_is_empty_on_every_side() {
        given(orderProjections.find(ORDER_ID)).willReturn(Optional.empty());
        given(paymentProjections.findByOrderId(ORDER_ID)).willReturn(Optional.empty());
        given(eventStore.findEventRefs(ORDER_ID, EventSource.ORDER)).willReturn(List.of());
        given(eventStore.findEventRefs(ORDER_ID, EventSource.PAYMENT)).willReturn(List.of());

        LocalOrderView view = new LocalViewLoader(orderProjections, paymentProjections, eventStore)
                .load(ORDER_ID);

        assertThat(view.order()).isEmpty();
        assertThat(view.payment()).isEmpty();
        assertThat(view.orderEvents()).isEmpty();
    }

    @Test
    void a_page_reports_how_many_pages_there_are() {
        assertThat(new PageResult<>(List.of(), 0, 20, 41).totalPages()).isEqualTo(3);
        assertThat(new PageResult<>(List.of(), 0, 20, 40).totalPages()).isEqualTo(2);
        assertThat(new PageResult<>(null, 0, 20, 0).content()).isEmpty();
    }

    private AuditIssue issue() {
        return new AuditIssue(1L, ORDER_ID, IssueStatus.OPEN, Severity.CRITICAL, 2, NOW, NOW, null, 7L);
    }
}
