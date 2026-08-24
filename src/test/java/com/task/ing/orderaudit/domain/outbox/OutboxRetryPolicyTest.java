package com.task.ing.orderaudit.domain.outbox;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxRetryPolicyTest {

    private static final Instant NOW = Instant.parse("2026-08-19T10:00:00Z");

    private final OutboxRetryPolicy policy =
            new OutboxRetryPolicy(4, Duration.ofSeconds(30), Duration.ofMinutes(10));

    @Test
    void retries_until_the_attempt_budget_is_spent() {
        assertThat(policy.canRetry(1)).isTrue();
        assertThat(policy.canRetry(3)).isTrue();
        assertThat(policy.canRetry(4)).isFalse();
        assertThat(policy.canRetry(5)).isFalse();
    }

    @Test
    void doubles_the_wait_after_every_failure() {
        assertThat(policy.nextAttemptAt(1, NOW)).isEqualTo(NOW.plusSeconds(30));
        assertThat(policy.nextAttemptAt(2, NOW)).isEqualTo(NOW.plusSeconds(60));
        assertThat(policy.nextAttemptAt(3, NOW)).isEqualTo(NOW.plusSeconds(120));
    }

    @Test
    void never_waits_longer_than_the_cap() {
        assertThat(policy.nextAttemptAt(20, NOW)).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
    }

    @Test
    void treats_a_zero_attempt_count_as_the_first_wait_rather_than_overflowing() {
        assertThat(policy.nextAttemptAt(0, NOW)).isEqualTo(NOW.plusSeconds(30));
    }

    @Test
    void accepts_the_smallest_sensible_configuration() {
        OutboxRetryPolicy single = new OutboxRetryPolicy(1, Duration.ofSeconds(1), Duration.ofSeconds(1));

        assertThat(single.canRetry(0)).isTrue();
        assertThat(single.canRetry(1)).isFalse();
        assertThat(single.nextAttemptAt(1, NOW)).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    void rejects_a_nonsensical_configuration() {
        assertThatThrownBy(() -> new OutboxRetryPolicy(0, Duration.ofSeconds(1), Duration.ofSeconds(2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxAttempts");
        assertThatThrownBy(() -> new OutboxRetryPolicy(3, Duration.ZERO, Duration.ofSeconds(2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("initialBackoff");
        assertThatThrownBy(() -> new OutboxRetryPolicy(3, Duration.ofSeconds(-1), Duration.ofSeconds(2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("initialBackoff");
        assertThatThrownBy(() -> new OutboxRetryPolicy(3, Duration.ofMinutes(5), Duration.ofSeconds(2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxBackoff");
    }
}
