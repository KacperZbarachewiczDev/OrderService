package com.task.ing.orderaudit.domain.notification;

import com.task.ing.orderaudit.domain.audit.AuditRun;
import com.task.ing.orderaudit.domain.audit.AuditRunStats;
import com.task.ing.orderaudit.domain.audit.AuditRunStatus;
import com.task.ing.orderaudit.domain.audit.AuditTrigger;
import com.task.ing.orderaudit.domain.audit.Severity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditNotificationComposerTest {

    private static final Instant STARTED = Instant.parse("2026-08-19T02:00:00Z");
    private static final Instant FINISHED = Instant.parse("2026-08-19T02:04:00Z");

    private final AuditNotificationComposer composer =
            new AuditNotificationComposer(2, "https://audit.internal/issues");

    @Test
    void names_the_run_and_the_damage_in_the_subject() {
        NotificationMessage message = composer.compose(
                List.of("ops@example.com"),
                run(new AuditRunStats(10, 3, 1, 7, 0)),
                List.of(digest("ORD-1", Severity.CRITICAL), digest("ORD-2", Severity.MAJOR)));

        assertThat(message.subject())
                .isEqualTo("[Order Audit] run #42: 3 orders with issues (1 critical)");
    }

    @Test
    void uses_the_singular_for_a_lone_problem_and_omits_a_critical_count_of_zero() {
        NotificationMessage message = composer.compose(
                List.of("ops@example.com"),
                run(new AuditRunStats(10, 1, 0, 1, 0)),
                List.of(digest("ORD-1", Severity.MAJOR)));

        assertThat(message.subject()).isEqualTo("[Order Audit] run #42: 1 order with issues");
    }

    @Test
    void spells_out_the_counters_and_the_affected_orders() {
        NotificationMessage message = composer.compose(
                List.of("ops@example.com"),
                run(new AuditRunStats(10, 2, 1, 5, 0)),
                List.of(digest("ORD-1", Severity.CRITICAL), digest("ORD-2", Severity.MAJOR)));

        assertThat(message.body())
                .contains("Audit run #42 finished.")
                .contains("Checked:      10 orders")
                .contains("With issues:  2")
                .contains("Resolved:     1")
                .contains("ORD-1")
                .contains("[CRITICAL]")
                .contains("ORDER_STATUS_MISMATCH")
                .contains("Full list: https://audit.internal/issues");
    }

    @Test
    void calls_out_orders_it_could_not_verify_separately_from_the_ones_it_found_wrong() {
        NotificationMessage message = composer.compose(
                List.of("ops@example.com"),
                run(new AuditRunStats(10, 1, 0, 1, 4)),
                List.of(digest("ORD-1", Severity.CRITICAL)));

        assertThat(message.body())
                .contains("Inconclusive: 4 orders could not be verified");
    }

    @Test
    void stays_silent_about_inconclusive_orders_when_there_were_none() {
        NotificationMessage message = composer.compose(
                List.of("ops@example.com"),
                run(new AuditRunStats(10, 1, 0, 1, 0)),
                List.of(digest("ORD-1", Severity.CRITICAL)));

        assertThat(message.body()).doesNotContain("Inconclusive");
    }

    @Test
    void lists_only_the_first_few_orders_and_counts_the_rest() {
        List<IssueDigest> digests = IntStream.rangeClosed(1, 5)
                .mapToObj(i -> digest("ORD-" + i, Severity.MAJOR))
                .toList();

        NotificationMessage message = composer.compose(
                List.of("ops@example.com"), run(new AuditRunStats(500, 137, 0, 400, 0)), digests);

        assertThat(message.body())
                .contains("ORD-1")
                .contains("ORD-2")
                .doesNotContain("ORD-3")
                .contains("... and 135 more");
    }

    @Test
    void handles_a_run_that_reported_no_individual_orders() {
        NotificationMessage message = composer.compose(
                List.of("ops@example.com"), run(new AuditRunStats(10, 0, 0, 0, 0)), List.of());

        assertThat(message.body()).contains("No individual issues were listed for this run.");
    }

    @Test
    void tolerates_a_null_digest_list() {
        assertThat(composer.compose(List.of("ops@example.com"), run(AuditRunStats.empty()), null).body())
                .contains("No individual issues were listed");
    }

    @Test
    void omits_the_link_when_none_is_configured() {
        assertThat(new AuditNotificationComposer(5, "  ")
                .compose(List.of("ops@example.com"), run(AuditRunStats.empty()), List.of()).body())
                .doesNotContain("Full list:");
        assertThat(new AuditNotificationComposer(5, null)
                .compose(List.of("ops@example.com"), run(AuditRunStats.empty()), List.of()).body())
                .doesNotContain("Full list:");
    }

    @Test
    void says_nothing_about_more_orders_when_it_listed_them_all() {
        NotificationMessage message = composer.compose(
                List.of("ops@example.com"), run(new AuditRunStats(10, 2, 0, 2, 0)),
                List.of(digest("ORD-1", Severity.MAJOR), digest("ORD-2", Severity.MAJOR)));

        assertThat(message.body()).doesNotContain("... and");
    }

    @Test
    void listing_a_single_issue_is_a_valid_configuration() {
        NotificationMessage message = new AuditNotificationComposer(1, "url").compose(
                List.of("ops@example.com"), run(new AuditRunStats(10, 2, 0, 2, 0)),
                List.of(digest("ORD-1", Severity.MAJOR), digest("ORD-2", Severity.MAJOR)));

        assertThat(message.body()).contains("ORD-1").doesNotContain("ORD-2").contains("... and 1 more");
    }

    @Test
    void refuses_to_build_a_message_nobody_would_receive() {
        assertThatThrownBy(() -> composer.compose(List.of(), run(AuditRunStats.empty()), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one recipient");
    }

    @Test
    void rejects_a_listing_limit_that_would_show_nothing() {
        assertThatThrownBy(() -> new AuditNotificationComposer(0, "url"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private AuditRun run(AuditRunStats stats) {
        return new AuditRun(42L, AuditTrigger.SCHEDULED, AuditRunStatus.COMPLETED,
                STARTED.minusSeconds(86_400), STARTED, STARTED, FINISHED, stats, null);
    }

    private IssueDigest digest(String orderId, Severity severity) {
        return new IssueDigest(orderId, severity, 2, List.of("ORDER_STATUS_MISMATCH", "ORDER_AMOUNT_MISMATCH"));
    }
}
