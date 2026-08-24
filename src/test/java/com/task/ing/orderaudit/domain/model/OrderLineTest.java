package com.task.ing.orderaudit.domain.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderLineTest {

    @Test
    void requires_a_product_and_a_price() {
        assertThatThrownBy(() -> new OrderLine(null, 1, Money.of("1.00", "PLN")))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OrderLine("P-1", 1, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void describes_itself_for_audit_findings() {
        assertThat(new OrderLine("P-1", 2, Money.of("75.00", "PLN")).describe())
                .isEqualTo("P-1 x2 @ 75.00 PLN");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejects_a_non_positive_quantity(int quantity) {
        assertThatThrownBy(() -> new OrderLine("P-1", quantity, Money.of("1.00", "PLN")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quantity must be positive");
    }

    @Test
    void indexes_snapshot_lines_by_product() {
        OrderSnapshot snapshot = new OrderSnapshot("ORD-1", "CUST-1", OrderStatus.CREATED, null,
                List.of(new OrderLine("P-1", 1, Money.of("1.00", "PLN")),
                        new OrderLine("P-2", 2, Money.of("2.00", "PLN"))), null);

        assertThat(snapshot.linesByProduct()).containsOnlyKeys("P-1", "P-2");
        assertThat(snapshot.hasLines()).isTrue();
    }

    @Test
    void a_snapshot_without_lines_reports_so() {
        OrderSnapshot snapshot =
                new OrderSnapshot("ORD-1", null, OrderStatus.CREATED, null, null, null);

        assertThat(snapshot.hasLines()).isFalse();
        assertThat(snapshot.lines()).isEmpty();
    }
}
