package com.task.ing.orderaudit.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderStateChangeTest {

    private static final Instant FIRST = Instant.parse("2026-08-19T10:00:00Z");
    private static final Instant SECOND = Instant.parse("2026-08-19T11:00:00Z");

    @Test
    void builds_the_first_snapshot_from_a_creation_event() {
        OrderStateChange change = new OrderStateChange(
                "ORD-1", "CUST-1", OrderStatus.CREATED, Money.of("150.00", "PLN"),
                List.of(new OrderLine("P-1", 2, Money.of("75.00", "PLN"))), FIRST);

        OrderSnapshot snapshot = change.applyTo(null);

        assertThat(snapshot.orderId()).isEqualTo("ORD-1");
        assertThat(snapshot.customerId()).isEqualTo("CUST-1");
        assertThat(snapshot.status()).isEqualTo(OrderStatus.CREATED);
        assertThat(snapshot.totalAmount()).isEqualTo(Money.of("150.00", "PLN"));
        assertThat(snapshot.lines()).hasSize(1);
        assertThat(snapshot.updatedAt()).isEqualTo(FIRST);
    }

    @Test
    void a_first_event_without_a_status_lands_as_unknown_rather_than_null() {
        OrderSnapshot snapshot =
                new OrderStateChange("ORD-1", null, null, null, null, FIRST).applyTo(null);

        assertThat(snapshot.status()).isEqualTo(OrderStatus.UNKNOWN);
        assertThat(snapshot.lines()).isEmpty();
    }

    @Test
    void a_partial_event_keeps_the_fields_it_says_nothing_about() {
        OrderSnapshot existing = new OrderStateChange(
                "ORD-1", "CUST-1", OrderStatus.CREATED, Money.of("150.00", "PLN"),
                List.of(new OrderLine("P-1", 2, Money.of("75.00", "PLN"))), FIRST).applyTo(null);

        OrderSnapshot updated = new OrderStateChange(
                "ORD-1", null, OrderStatus.COMPLETED, null, null, SECOND).applyTo(existing);

        assertThat(updated.status()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(updated.customerId()).isEqualTo("CUST-1");
        assertThat(updated.totalAmount()).isEqualTo(Money.of("150.00", "PLN"));
        assertThat(updated.lines()).hasSize(1);
        assertThat(updated.updatedAt()).isEqualTo(SECOND);
    }

    @Test
    void an_event_that_does_carry_items_replaces_them() {
        OrderSnapshot existing = new OrderStateChange(
                "ORD-1", "CUST-1", OrderStatus.CREATED, null,
                List.of(new OrderLine("P-1", 2, Money.of("75.00", "PLN"))), FIRST).applyTo(null);

        OrderSnapshot updated = new OrderStateChange(
                "ORD-1", null, null, null,
                List.of(new OrderLine("P-2", 1, Money.of("10.00", "PLN"))), SECOND).applyTo(existing);

        assertThat(updated.lines()).singleElement()
                .extracting(OrderLine::productId).isEqualTo("P-2");
    }

    @Test
    void an_empty_item_list_is_an_explicit_removal_not_a_missing_field() {
        OrderSnapshot existing = new OrderStateChange(
                "ORD-1", "CUST-1", OrderStatus.CREATED, null,
                List.of(new OrderLine("P-1", 2, Money.of("75.00", "PLN"))), FIRST).applyTo(null);

        OrderSnapshot updated = new OrderStateChange(
                "ORD-1", null, null, null, List.of(), SECOND).applyTo(existing);

        assertThat(updated.lines()).isEmpty();
    }

    @Test
    void an_event_that_does_state_a_field_overrides_the_one_held() {
        OrderSnapshot existing = new OrderStateChange(
                "ORD-1", "CUST-1", OrderStatus.CREATED, Money.of("150.00", "PLN"), null, FIRST)
                .applyTo(null);

        OrderSnapshot updated = new OrderStateChange(
                "ORD-1", "CUST-2", OrderStatus.CANCELLED, Money.of("10.00", "PLN"), null, SECOND)
                .applyTo(existing);

        assertThat(updated.customerId()).isEqualTo("CUST-2");
        assertThat(updated.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(updated.totalAmount()).isEqualTo(Money.of("10.00", "PLN"));
    }

    @Test
    void an_event_that_states_nothing_leaves_every_field_as_it_was() {
        OrderSnapshot existing = new OrderStateChange(
                "ORD-1", "CUST-1", OrderStatus.CREATED, Money.of("150.00", "PLN"), null, FIRST)
                .applyTo(null);

        OrderSnapshot updated =
                new OrderStateChange("ORD-1", null, null, null, null, SECOND).applyTo(existing);

        assertThat(updated.customerId()).isEqualTo("CUST-1");
        assertThat(updated.status()).isEqualTo(OrderStatus.CREATED);
        assertThat(updated.totalAmount()).isEqualTo(Money.of("150.00", "PLN"));
        assertThat(updated.updatedAt()).isEqualTo(SECOND);
    }

    @Test
    void keeps_the_identity_of_the_order_it_is_folded_onto() {
        OrderSnapshot existing =
                new OrderStateChange("ORD-1", null, OrderStatus.CREATED, null, null, FIRST).applyTo(null);
        OrderSnapshot updated =
                new OrderStateChange("ORD-1", null, OrderStatus.PAID, null, null, SECOND).applyTo(existing);

        assertThat(updated.orderId()).isEqualTo("ORD-1");
    }

    @Test
    void requires_an_order_id_and_a_timestamp() {
        assertThatThrownBy(() -> new OrderStateChange(null, null, null, null, null, FIRST))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OrderStateChange("ORD-1", null, null, null, null, null))
                .isInstanceOf(NullPointerException.class);
    }
}
