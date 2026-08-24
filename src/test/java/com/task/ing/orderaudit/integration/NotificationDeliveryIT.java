package com.task.ing.orderaudit.integration;

import com.task.ing.orderaudit.application.port.in.DispatchNotificationsUseCase;
import com.task.ing.orderaudit.application.port.in.RunAuditUseCase;
import com.task.ing.orderaudit.domain.audit.AuditTrigger;
import com.task.ing.orderaudit.support.AbstractIntegrationTest;
import com.task.ing.orderaudit.support.Payloads;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationDeliveryIT extends AbstractIntegrationTest {

    @Autowired
    private RunAuditUseCase runAudit;

    @Autowired
    private DispatchNotificationsUseCase dispatchNotifications;

    @Test
    @DisplayName("the audit's findings reach an operator's mailbox")
    void delivers_the_audit_notification() throws Exception {
        givenAnOrderThatWillFailTheAudit("ORD-N1");
        runAudit.run(AuditTrigger.MANUAL);

        assertThat(dispatchNotifications.dispatchDue()).isEqualTo(1);

        MimeMessage[] received = SMTP.getReceivedMessages();
        assertThat(received).hasSize(1);
        assertThat(received[0].getSubject()).contains("1 order with issues");
        assertThat(received[0].getAllRecipients()[0]).hasToString("ops@example.com");
        assertThat(received[0].getFrom()[0]).hasToString("order-audit@example.com");
        assertThat(received[0].getContent().toString())
                .contains("ORD-N1")
                .contains("ORDER_STATUS_MISMATCH")
                .contains("Full list:");

        Map<String, Object> row = jdbcTemplate.queryForMap("select * from notification_outbox");
        assertThat(row.get("status")).isEqualTo("SENT");
        assertThat(row.get("sent_at")).isNotNull();
    }

    @Test
    @DisplayName("a delivered message is not delivered again on the next pass")
    void does_not_send_the_same_notification_twice() {
        givenAnOrderThatWillFailTheAudit("ORD-N2");
        runAudit.run(AuditTrigger.MANUAL);

        assertThat(dispatchNotifications.dispatchDue()).isEqualTo(1);
        assertThat(dispatchNotifications.dispatchDue()).isZero();
        assertThat(SMTP.getReceivedMessages()).hasSize(1);
    }

    @Test
    @DisplayName("a mail server outage delays the notification and it goes out once the server returns")
    void retries_until_the_mail_server_comes_back() {
        givenAnOrderThatWillFailTheAudit("ORD-N3");
        runAudit.run(AuditTrigger.MANUAL);

        SMTP.stop();
        assertThat(dispatchNotifications.dispatchDue()).isZero();

        Map<String, Object> afterFailure = jdbcTemplate.queryForMap("select * from notification_outbox");
        assertThat(afterFailure.get("status")).isEqualTo("PENDING");
        assertThat(afterFailure.get("attempts")).isEqualTo(1);
        assertThat(afterFailure.get("last_error")).isNotNull();

        SMTP.start();
        awaitAssertion(() -> assertThat(dispatchNotifications.dispatchDue()).isEqualTo(1));

        assertThat(SMTP.getReceivedMessages()).hasSize(1);
        assertThat(jdbcTemplate.queryForMap("select * from notification_outbox").get("status"))
                .isEqualTo("SENT");
    }

    @Test
    @DisplayName("a message nobody can deliver is parked for a human instead of retried forever")
    void gives_up_after_the_configured_number_of_attempts() {
        givenAnOrderThatWillFailTheAudit("ORD-N4");
        runAudit.run(AuditTrigger.MANUAL);

        SMTP.stop();
        try {
            for (int attempt = 0; attempt < 3; attempt++) {
                dispatchNotifications.dispatchDue();
                jdbcTemplate.update("update notification_outbox set next_attempt_at = now()");
            }
        } finally {
            SMTP.start();
        }

        Map<String, Object> row = jdbcTemplate.queryForMap("select * from notification_outbox");
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("attempts")).isEqualTo(3);
        assertThat(dispatchNotifications.dispatchDue()).isZero();
    }

    @Test
    @DisplayName("a clean audit sends nothing at all")
    void stays_silent_when_there_is_nothing_to_report() {
        orderService.modifiedSince();
        paymentService.modifiedSince();

        runAudit.run(AuditTrigger.MANUAL);

        assertThat(dispatchNotifications.dispatchDue()).isZero();
        assertThat(SMTP.getReceivedMessages()).isEmpty();
    }

    @Test
    @DisplayName("the mail carries the window and the counters, not just a list of orders")
    void the_mail_explains_what_the_run_did() throws Exception {
        givenAnOrderThatWillFailTheAudit("ORD-N5");
        runAudit.run(AuditTrigger.MANUAL);

        dispatchNotifications.dispatchDue();

        String body = SMTP.getReceivedMessages()[0].getContent().toString();
        assertThat(body)
                .contains("Window:")
                .contains("Checked:")
                .contains("With issues:  1")
                .contains("Discrepancies:");
    }

    @Test
    @DisplayName("orders the run could not verify are called out separately in the mail")
    void the_mail_separates_the_unverifiable_from_the_broken() throws Exception {
        givenAnOrderThatWillFailTheAudit("ORD-N6");
        givenReceivedOrderEvent(Payloads.orderEvent("EVT-N7-1", "ORD-N7", "ORDER_CREATED", Payloads.T0));
        orderService.modifiedSince("ORD-N6", "ORD-N7");
        orderService.aggregateUnavailable("ORD-N7");
        paymentService.aggregateMissing("ORD-N7");
        paymentService.eventCount("ORD-N7", 0);
        paymentService.events("ORD-N7");

        runAudit.run(AuditTrigger.MANUAL);
        dispatchNotifications.dispatchDue();

        assertThat(SMTP.getReceivedMessages()[0].getContent().toString())
                .contains("Inconclusive: 1 orders could not be verified")
                .contains("ORD-N6");
    }

    private void givenAnOrderThatWillFailTheAudit(String orderId) {
        givenReceivedOrderEvent(Payloads.orderEvent("EVT-" + orderId + "-1", orderId, "ORDER_CREATED", Payloads.T0));
        givenReceivedOrderEvent(Payloads.orderEvent("EVT-" + orderId + "-2", orderId, "ORDER_COMPLETED", Payloads.T2));

        orderService.modifiedSince(orderId);
        orderService.aggregate(orderId, Payloads.order(orderId, "CANCELLED", "150.00"));
        orderService.eventCount(orderId, 2);
        orderService.events(orderId,
                Payloads.orderEvent("EVT-" + orderId + "-1", orderId, "ORDER_CREATED", Payloads.T0),
                Payloads.orderEvent("EVT-" + orderId + "-2", orderId, "ORDER_COMPLETED", Payloads.T2));

        paymentService.modifiedSince();
        paymentService.aggregateMissing(orderId);
        paymentService.eventCount(orderId, 0);
        paymentService.events(orderId);
    }
}
