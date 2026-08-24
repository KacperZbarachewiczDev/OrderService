package com.task.ing.orderaudit.domain;

import com.task.ing.orderaudit.application.service.OrderAuditOutcome;
import com.task.ing.orderaudit.domain.audit.AuditIssue;
import com.task.ing.orderaudit.domain.audit.AuditIssueDetail;
import com.task.ing.orderaudit.domain.audit.Discrepancy;
import com.task.ing.orderaudit.domain.audit.DiscrepancyType;
import com.task.ing.orderaudit.domain.audit.IssueStatus;
import com.task.ing.orderaudit.domain.audit.LocalOrderView;
import com.task.ing.orderaudit.domain.audit.Severity;
import com.task.ing.orderaudit.domain.audit.SourceOrderView;
import com.task.ing.orderaudit.domain.model.EventOrigin;
import com.task.ing.orderaudit.domain.model.EventSource;
import com.task.ing.orderaudit.domain.model.IngestedEvent;
import com.task.ing.orderaudit.domain.model.OrderSnapshot;
import com.task.ing.orderaudit.domain.model.OrderStatus;
import com.task.ing.orderaudit.domain.model.PaymentSnapshot;
import com.task.ing.orderaudit.domain.model.PaymentStatus;
import com.task.ing.orderaudit.domain.notification.IssueDigest;
import com.task.ing.orderaudit.domain.notification.NotificationMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainInvariantsTest {

    private static final Instant NOW = Instant.parse("2026-08-19T10:00:00Z");

    @Test
    void a_local_view_with_nothing_in_it_is_usable_rather_than_null_ridden() {
        LocalOrderView view = new LocalOrderView("ORD-1", null, null, null, null);

        assertThat(view.order()).isEmpty();
        assertThat(view.payment()).isEmpty();
        assertThat(view.orderEvents()).isEmpty();
        assertThat(view.paymentEvents()).isEmpty();
        assertThatThrownBy(() -> new LocalOrderView(null, null, null, null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void a_source_view_with_nothing_in_it_is_usable_rather_than_null_ridden() {
        SourceOrderView view = new SourceOrderView("ORD-1", null, null, 0, 0, null, null, false);

        assertThat(view.order()).isEmpty();
        assertThat(view.payment()).isEmpty();
        assertThat(view.orderEvents()).isEmpty();
        assertThat(view.paymentEvents()).isEmpty();
        assertThatThrownBy(() -> new SourceOrderView(null, null, null, 0, 0, null, null, false))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void the_views_defend_their_collections_against_later_change() {
        List<com.task.ing.orderaudit.domain.model.EventRef> mutable = new java.util.ArrayList<>();
        mutable.add(com.task.ing.orderaudit.domain.model.EventRef.of("EVT-1", "ORDER_CREATED", NOW));

        LocalOrderView view = new LocalOrderView(
                "ORD-1", Optional.empty(), Optional.empty(), mutable, List.of());
        mutable.clear();

        assertThat(view.orderEvents()).hasSize(1);
    }

    @Test
    void a_snapshot_without_a_status_is_read_as_unknown_rather_than_null() {
        OrderSnapshot order = new OrderSnapshot("ORD-1", null, null, null, null, null);
        PaymentSnapshot payment = new PaymentSnapshot("PAY-1", "ORD-1", null, null, null);

        assertThat(order.status()).isEqualTo(OrderStatus.UNKNOWN);
        assertThat(order.lines()).isEmpty();
        assertThat(payment.status()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThatThrownBy(() -> new OrderSnapshot(null, null, null, null, null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PaymentSnapshot(null, "ORD-1", null, null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PaymentSnapshot("PAY-1", null, null, null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void an_archived_event_always_has_a_payload_even_when_the_message_had_none() {
        IngestedEvent event = new IngestedEvent("EVT-1", EventSource.ORDER, "ORD-1", "ORD-1",
                "ORDER_CREATED", NOW, null, null, NOW, EventOrigin.KAFKA);

        assertThat(event.payload()).isEqualTo("{}");
        assertThat(event.ref().eventId()).isEqualTo("EVT-1");
        assertThat(event.ref().sequenceNo()).isNull();
    }

    @Test
    void an_archived_event_refuses_to_exist_without_its_identity() {
        assertThatThrownBy(() -> new IngestedEvent(null, EventSource.ORDER, "ORD-1", "ORD-1",
                "ORDER_CREATED", NOW, null, "{}", NOW, EventOrigin.KAFKA))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new IngestedEvent("EVT-1", null, "ORD-1", "ORD-1",
                "ORDER_CREATED", NOW, null, "{}", NOW, EventOrigin.KAFKA))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new IngestedEvent("EVT-1", EventSource.ORDER, "ORD-1", "ORD-1",
                "ORDER_CREATED", NOW, null, "{}", NOW, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void a_notification_needs_a_subject_a_body_and_somebody_to_send_it_to() {
        assertThatThrownBy(() -> new NotificationMessage(null, "subject", "body"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NotificationMessage(List.of("a@example.com"), null, "body"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new NotificationMessage(List.of("a@example.com"), "subject", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void a_digest_without_finding_types_still_names_its_order() {
        IssueDigest digest = new IssueDigest("ORD-1", Severity.MAJOR, 1, null);

        assertThat(digest.types()).isEmpty();
        assertThatThrownBy(() -> new IssueDigest(null, Severity.MAJOR, 1, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void an_issue_without_findings_reads_as_an_empty_list() {
        AuditIssue issue = new AuditIssue(1L, "ORD-1", IssueStatus.RESOLVED, Severity.MINOR,
                0, NOW, NOW, NOW, null);

        assertThat(new AuditIssueDetail(issue, null).discrepancies()).isEmpty();
        assertThat(new AuditIssueDetail(issue, List.of(statusMismatch())).discrepancies()).hasSize(1);
    }

    @Test
    void an_audit_outcome_without_findings_reads_as_an_empty_list() {
        assertThat(new OrderAuditOutcome(OrderAuditOutcome.Kind.CLEAN, null, null).discrepancies())
                .isEmpty();
        assertThat(OrderAuditOutcome.issue(List.of(statusMismatch())).discrepancies()).hasSize(1);
        assertThat(OrderAuditOutcome.inconclusive("down").reason()).isEqualTo("down");
    }

    @Test
    void a_finding_refuses_to_exist_without_a_type() {
        assertThatThrownBy(() -> new Discrepancy(null, "status", "a", "b"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void a_finding_describes_itself_whichever_parts_are_present() {
        assertThat(new Discrepancy(DiscrepancyType.ORDER_STATUS_MISMATCH, null, "PAID", "CREATED").describe())
                .isEqualTo("ORDER_STATUS_MISMATCH: source=PAID, local=CREATED");
        assertThat(new Discrepancy(DiscrepancyType.ORDER_STATUS_MISMATCH, "status", null, "CREATED").describe())
                .isEqualTo("ORDER_STATUS_MISMATCH [status]: source=null, local=CREATED");
        assertThat(new Discrepancy(DiscrepancyType.ORDER_STATUS_MISMATCH, "status", "PAID", null).describe())
                .isEqualTo("ORDER_STATUS_MISMATCH [status]: source=PAID, local=null");
    }

    private Discrepancy statusMismatch() {
        return Discrepancy.of(DiscrepancyType.ORDER_STATUS_MISMATCH, "status", "PAID", "CREATED");
    }
}
