package com.task.ing.orderaudit.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Nested
    @DisplayName("equality ignores how the source system wrote the number")
    class Equality {
        @Test
        void treats_trailing_zeros_as_the_same_amount() {
            assertThat(Money.of("150.00", "PLN")).isEqualTo(Money.of("150", "PLN"));
            assertThat(Money.of("150.00", "PLN")).hasSameHashCodeAs(Money.of("150", "PLN"));
        }

        @Test
        void distinguishes_amounts_that_really_differ() {
            assertThat(Money.of("150.01", "PLN")).isNotEqualTo(Money.of("150.00", "PLN"));
        }

        @Test
        void distinguishes_currencies() {
            assertThat(Money.of("150.00", "PLN")).isNotEqualTo(Money.of("150.00", "EUR"));
        }

        @Test
        void is_not_equal_to_another_type() {
            assertThat(Money.of("1.00", "PLN")).isNotEqualTo("1.00 PLN");
        }

        @Test
        void zero_written_two_ways_is_still_zero() {
            assertThat(Money.of("0.00", "PLN")).isEqualTo(Money.of("0", "PLN"));
            assertThat(Money.of("0.00", "PLN")).hasSameHashCodeAs(Money.of("0", "PLN"));
        }
    }

    @Test
    void normalises_the_currency_code() {
        assertThat(Money.of("1.00", " pln ").currency()).isEqualTo("PLN");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PL", "PLNX", ""})
    void rejects_currency_codes_that_are_not_three_letters(String currency) {
        assertThatThrownBy(() -> Money.of("1.00", currency))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("3 letter ISO code");
    }

    @Test
    void rejects_missing_parts() {
        assertThatThrownBy(() -> new Money(null, "PLN")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Money(BigDecimal.ONE, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void compares_currency_and_amount_separately() {
        Money base = Money.of("150.00", "PLN");
        assertThat(base.sameCurrencyAs(Money.of("1.00", "PLN"))).isTrue();
        assertThat(base.sameCurrencyAs(Money.of("150.00", "EUR"))).isFalse();
        assertThat(base.sameCurrencyAs(null)).isFalse();
        assertThat(base.sameAmountAs(Money.of("150", "EUR"))).isTrue();
        assertThat(base.sameAmountAs(Money.of("150.01", "PLN"))).isFalse();
        assertThat(base.sameAmountAs(null)).isFalse();
    }

    @Test
    void amounts_that_differ_do_not_collide_in_a_hash_set() {
        assertThat(Money.of("150.00", "PLN").hashCode())
                .isNotEqualTo(Money.of("150.01", "PLN").hashCode());
        assertThat(Money.of("150.00", "PLN").hashCode())
                .isNotEqualTo(Money.of("150.00", "EUR").hashCode());
    }

    @Test
    void builds_from_a_decimal_as_well_as_from_text() {
        assertThat(Money.of(new BigDecimal("150.00"), "PLN")).isEqualTo(Money.of("150.00", "PLN"));
    }

    @Test
    void formats_with_two_decimals_and_no_scientific_notation() {
        assertThat(Money.of("150", "PLN").format()).isEqualTo("150.00 PLN");
        assertThat(Money.of("1200.005", "PLN").format()).isEqualTo("1200.01 PLN");
        assertThat(Money.of("150.00", "PLN")).hasToString("150.00 PLN");
    }
}
