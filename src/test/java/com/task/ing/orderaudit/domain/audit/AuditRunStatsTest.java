package com.task.ing.orderaudit.domain.audit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuditRunStatsTest {

    @Test
    void starts_at_zero_and_reports_no_findings() {
        AuditRunStats stats = AuditRunStats.empty();

        assertThat(stats.ordersChecked()).isZero();
        assertThat(stats.hasFindings()).isFalse();
    }

    @Test
    void a_clean_order_counts_as_checked_only() {
        AuditRunStats stats = AuditRunStats.empty().withChecked();

        assertThat(stats.ordersChecked()).isEqualTo(1);
        assertThat(stats.ordersWithIssues()).isZero();
        assertThat(stats.discrepanciesFound()).isZero();
        assertThat(stats.hasFindings()).isFalse();
    }

    @Test
    void an_order_with_findings_counts_its_discrepancies() {
        AuditRunStats stats = AuditRunStats.empty().withIssue(3).withIssue(2);

        assertThat(stats.ordersChecked()).isEqualTo(2);
        assertThat(stats.ordersWithIssues()).isEqualTo(2);
        assertThat(stats.discrepanciesFound()).isEqualTo(5);
        assertThat(stats.hasFindings()).isTrue();
    }

    @Test
    void a_newly_clean_order_counts_as_resolved_and_checked() {
        AuditRunStats stats = AuditRunStats.empty().withResolved();

        assertThat(stats.ordersChecked()).isEqualTo(1);
        assertThat(stats.ordersResolved()).isEqualTo(1);
        assertThat(stats.ordersWithIssues()).isZero();
    }

    @Test
    void an_unverifiable_order_is_counted_apart_and_never_as_checked() {
        AuditRunStats stats = AuditRunStats.empty().withInconclusive();

        assertThat(stats.ordersInconclusive()).isEqualTo(1);
        assertThat(stats.ordersChecked()).isZero();
        assertThat(stats.ordersWithIssues()).isZero();
        assertThat(stats.hasFindings()).isFalse();
    }
}
