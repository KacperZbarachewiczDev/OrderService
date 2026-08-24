package com.task.ing.orderaudit.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentStateChangeTest {

    private static final Instant FIRST = Instant.parse("2026-08-19T10:00:00Z");
    private static final Instant SECOND = Instant.parse("2026-08-19T11:00:00Z");

    @Test
    void builds_the_first_snapshot() {
        PaymentSnapshot snapshot = new PaymentStateChange(
                "PAY-1", "ORD-1", PaymentStatus.PENDING, Money.of("150.00", "PLN"), FIRST).applyTo(null);

        assertThat(snapshot.paymentId()).isEqualTo("PAY-1");
        assertThat(snapshot.orderId()).isEqualTo("ORD-1");
        assertThat(snapshot.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(snapshot.amount()).isEqualTo(Money.of("150.00", "PLN"));
        assertThat(snapshot.updatedAt()).isEqualTo(FIRST);
    }

    @Test
    void a_status_only_event_keeps_the_amount() {
        PaymentSnapshot existing = new PaymentStateChange(
                "PAY-1", "ORD-1", PaymentStatus.PENDING, Money.of("150.00", "PLN"), FIRST).applyTo(null);

        PaymentSnapshot updated = new PaymentStateChange(
                "PAY-1", "ORD-1", PaymentStatus.PAID, null, SECOND).applyTo(existing);

        assertThat(updated.status()).isEqualTo(PaymentStatus.PAID);
        assertThat(updated.amount()).isEqualTo(Money.of("150.00", "PLN"));
        assertThat(updated.updatedAt()).isEqualTo(SECOND);
    }

    @Test
    void an_amount_only_event_keeps_the_status() {
        PaymentSnapshot existing = new PaymentStateChange(
                "PAY-1", "ORD-1", PaymentStatus.PAID, Money.of("150.00", "PLN"), FIRST).applyTo(null);

        PaymentSnapshot updated = new PaymentStateChange(
                "PAY-1", "ORD-1", null, Money.of("120.00", "PLN"), SECOND).applyTo(existing);

        assertThat(updated.status()).isEqualTo(PaymentStatus.PAID);
        assertThat(updated.amount()).isEqualTo(Money.of("120.00", "PLN"));
    }

    @Test
    void an_event_that_states_nothing_leaves_the_payment_as_it_was() {
        PaymentSnapshot existing = new PaymentStateChange(
                "PAY-1", "ORD-1", PaymentStatus.PAID, Money.of("150.00", "PLN"), FIRST).applyTo(null);

        PaymentSnapshot updated =
                new PaymentStateChange("PAY-1", "ORD-1", null, null, SECOND).applyTo(existing);

        assertThat(updated.status()).isEqualTo(PaymentStatus.PAID);
        assertThat(updated.amount()).isEqualTo(Money.of("150.00", "PLN"));
        assertThat(updated.updatedAt()).isEqualTo(SECOND);
    }

    @Test
    void a_first_event_without_a_status_lands_as_unknown() {
        PaymentSnapshot snapshot =
                new PaymentStateChange("PAY-1", "ORD-1", null, null, FIRST).applyTo(null);

        assertThat(snapshot.status()).isEqualTo(PaymentStatus.UNKNOWN);
    }
}
