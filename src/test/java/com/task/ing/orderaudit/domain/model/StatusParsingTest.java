package com.task.ing.orderaudit.domain.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class StatusParsingTest {

    @Test
    void reads_known_order_statuses_case_insensitively() {
        assertThat(OrderStatus.fromExternal("completed")).isEqualTo(OrderStatus.COMPLETED);
        assertThat(OrderStatus.fromExternal(" PAID ")).isEqualTo(OrderStatus.PAID);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "ON_THE_MOON"})
    void falls_back_to_unknown_rather_than_failing_ingestion(String raw) {
        assertThat(OrderStatus.fromExternal(raw)).isEqualTo(OrderStatus.UNKNOWN);
        assertThat(PaymentStatus.fromExternal(raw)).isEqualTo(PaymentStatus.UNKNOWN);
    }

    @Test
    void knows_which_order_statuses_end_the_lifecycle() {
        assertThat(OrderStatus.COMPLETED.isTerminal()).isTrue();
        assertThat(OrderStatus.CANCELLED.isTerminal()).isTrue();
        assertThat(OrderStatus.PAID.isTerminal()).isFalse();
        assertThat(OrderStatus.CREATED.isTerminal()).isFalse();
        assertThat(OrderStatus.UNKNOWN.isTerminal()).isFalse();
    }

    @Test
    void knows_which_payment_statuses_are_settled() {
        assertThat(PaymentStatus.PAID.isSettled()).isTrue();
        assertThat(PaymentStatus.REFUNDED.isSettled()).isTrue();
        assertThat(PaymentStatus.PENDING.isSettled()).isFalse();
        assertThat(PaymentStatus.FAILED.isSettled()).isFalse();
    }

    @Test
    void reads_payment_statuses() {
        assertThat(PaymentStatus.fromExternal("paid")).isEqualTo(PaymentStatus.PAID);
        assertThat(PaymentStatus.fromExternal("AUTHORIZED")).isEqualTo(PaymentStatus.AUTHORIZED);
    }
}
