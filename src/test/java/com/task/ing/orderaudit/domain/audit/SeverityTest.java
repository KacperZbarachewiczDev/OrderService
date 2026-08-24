package com.task.ing.orderaudit.domain.audit;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SeverityTest {

    @Test
    void ranks_from_minor_to_critical() {
        assertThat(Severity.CRITICAL.isAtLeast(Severity.MAJOR)).isTrue();
        assertThat(Severity.MAJOR.isAtLeast(Severity.MAJOR)).isTrue();
        assertThat(Severity.MINOR.isAtLeast(Severity.MAJOR)).isFalse();
    }

    @Test
    void picks_the_worse_of_two() {
        assertThat(Severity.max(Severity.MINOR, Severity.CRITICAL)).isEqualTo(Severity.CRITICAL);
        assertThat(Severity.max(Severity.CRITICAL, Severity.MINOR)).isEqualTo(Severity.CRITICAL);
        assertThat(Severity.max(Severity.MAJOR, Severity.MAJOR)).isEqualTo(Severity.MAJOR);
        assertThat(Severity.max(Severity.MINOR, Severity.MAJOR)).isEqualTo(Severity.MAJOR);
        assertThat(Severity.max(Severity.MAJOR, Severity.MINOR)).isEqualTo(Severity.MAJOR);
    }

    @Test
    void tolerates_a_missing_side_so_it_can_seed_a_fold() {
        assertThat(Severity.max(null, Severity.MINOR)).isEqualTo(Severity.MINOR);
        assertThat(Severity.max(Severity.MINOR, null)).isEqualTo(Severity.MINOR);
        assertThat(Severity.max(null, null)).isNull();
    }

    @Test
    void reports_the_worst_severity_across_a_set_of_findings() {
        List<Discrepancy> findings = List.of(
                Discrepancy.of(DiscrepancyType.ORDER_CUSTOMER_MISMATCH, "customerId", "A", "B"),
                Discrepancy.of(DiscrepancyType.ORDER_STATUS_MISMATCH, "status", "PAID", "CREATED"));

        assertThat(Discrepancy.highestSeverity(findings)).isEqualTo(Severity.CRITICAL);
        assertThat(Discrepancy.highestSeverity(List.of())).isNull();
    }

    @Test
    void describes_a_finding_in_a_way_an_operator_can_read() {
        assertThat(Discrepancy.of(DiscrepancyType.ORDER_STATUS_MISMATCH, "status", "PAID", "CREATED")
                .describe())
                .isEqualTo("ORDER_STATUS_MISMATCH [status]: source=PAID, local=CREATED");
        assertThat(Discrepancy.of(DiscrepancyType.ORDER_MISSING_LOCALLY, "orderId").describe())
                .isEqualTo("ORDER_MISSING_LOCALLY [orderId]");
        assertThat(new Discrepancy(DiscrepancyType.ORDER_MISSING_LOCALLY, null, null, null).describe())
                .isEqualTo("ORDER_MISSING_LOCALLY");
    }
}
