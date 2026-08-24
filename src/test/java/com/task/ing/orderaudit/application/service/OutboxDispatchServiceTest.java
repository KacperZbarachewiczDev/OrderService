package com.task.ing.orderaudit.application.service;

import com.task.ing.orderaudit.application.port.out.MailSenderPort;
import com.task.ing.orderaudit.application.port.out.NotificationOutboxPort;
import com.task.ing.orderaudit.domain.notification.NotificationMessage;
import com.task.ing.orderaudit.domain.outbox.OutboxRetryPolicy;
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
class OutboxDispatchServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-19T12:00:00Z");

    @Mock
    private NotificationOutboxPort outbox;
    @Mock
    private MailSenderPort mailSender;

    private final OutboxRetryPolicy retryPolicy =
            new OutboxRetryPolicy(3, Duration.ofSeconds(30), Duration.ofMinutes(10));

    private OutboxDispatchService service() {
        return new OutboxDispatchService(
                outbox, mailSender, retryPolicy, TestProperties.audit(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void does_nothing_when_no_message_is_due() {
        given(outbox.claimDue(NOW, 20)).willReturn(List.of());

        assertThat(service().dispatchDue()).isZero();
        verify(mailSender, never()).send(any());
    }

    @Test
    void delivers_and_marks_every_due_message() {
        given(outbox.claimDue(NOW, 20)).willReturn(List.of(pending(1L, 1), pending(2L, 1)));

        assertThat(service().dispatchDue()).isEqualTo(2);
        verify(mailSender, org.mockito.Mockito.times(2)).send(any());
        verify(outbox).markSent(1L, NOW);
        verify(outbox).markSent(2L, NOW);
    }

    @Test
    void reschedules_a_message_the_mail_server_refused() {
        given(outbox.claimDue(NOW, 20)).willReturn(List.of(pending(1L, 1)));
        willThrow(new IllegalStateException("smtp down")).given(mailSender).send(any());

        assertThat(service().dispatchDue()).isZero();
        verify(outbox).reschedule(eq(1L), anyString(), eq(NOW.plusSeconds(30)));
        verify(outbox, never()).markFailed(anyLong(), anyString(), any());
        verify(outbox, never()).markSent(anyLong(), any());
    }

    @Test
    void backs_off_further_on_each_successive_failure() {
        given(outbox.claimDue(NOW, 20)).willReturn(List.of(pending(1L, 2)));
        willThrow(new IllegalStateException("smtp down")).given(mailSender).send(any());

        service().dispatchDue();

        verify(outbox).reschedule(eq(1L), anyString(), eq(NOW.plusSeconds(60)));
    }

    @Test
    void parks_a_message_that_has_exhausted_its_attempts() {
        given(outbox.claimDue(NOW, 20)).willReturn(List.of(pending(1L, 3)));
        willThrow(new IllegalStateException("smtp down")).given(mailSender).send(any());

        assertThat(service().dispatchDue()).isZero();
        verify(outbox).markFailed(eq(1L), anyString(), eq(NOW));
        verify(outbox, never()).reschedule(anyLong(), anyString(), any());
    }

    @Test
    void a_failing_message_does_not_hold_up_the_others() {
        given(outbox.claimDue(NOW, 20)).willReturn(List.of(pending(1L, 1), pending(2L, 1)));
        willThrow(new IllegalStateException("rejected")).given(mailSender).send(
                org.mockito.ArgumentMatchers.argThat(message -> message.subject().endsWith("#1")));

        assertThat(service().dispatchDue()).isEqualTo(1);
        verify(outbox).reschedule(eq(1L), anyString(), any());
        verify(outbox).markSent(2L, NOW);
    }

    private NotificationOutboxPort.PendingNotification pending(long id, int attempts) {
        return new NotificationOutboxPort.PendingNotification(
                id,
                new NotificationMessage(List.of("ops@example.com"), "audit run #" + id, "body"),
                attempts);
    }
}
