package com.task.ing.orderaudit.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventRefTest {

    private static final Instant T1 = Instant.parse("2026-08-19T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-08-19T11:00:00Z");

    @Test
    void orders_by_time_first() {
        EventRef earlier = new EventRef("EVT-9", "ORDER_CREATED", T1, 99L);
        EventRef later = new EventRef("EVT-1", "ORDER_PAID", T2, 1L);
        assertThat(earlier).isLessThan(later);
    }

    @Test
    void falls_back_to_the_sequence_number_when_timestamps_tie() {
        EventRef first = new EventRef("EVT-9", "ORDER_CREATED", T1, 1L);
        EventRef second = new EventRef("EVT-1", "ORDER_PAID", T1, 2L);
        assertThat(first).isLessThan(second);
    }

    @Test
    void treats_a_missing_sequence_as_older_than_any_sequence() {
        EventRef unnumbered = new EventRef("EVT-9", "ORDER_CREATED", T1, null);
        EventRef numbered = new EventRef("EVT-1", "ORDER_PAID", T1, 0L);
        assertThat(unnumbered).isLessThan(numbered);
    }

    @Test
    void falls_back_to_the_event_id_so_ordering_is_total() {
        EventRef a = new EventRef("EVT-1", "ORDER_CREATED", T1, 5L);
        EventRef b = new EventRef("EVT-2", "ORDER_PAID", T1, 5L);
        assertThat(a).isLessThan(b);
        assertThat(a.compareTo(new EventRef("EVT-1", "ORDER_CREATED", T1, 5L))).isZero();
    }

    @Test
    void sorts_a_shuffled_stream_deterministically() {
        EventRef created = new EventRef("EVT-3", "ORDER_CREATED", T1, 1L);
        EventRef paid = new EventRef("EVT-1", "ORDER_PAID", T1, 2L);
        EventRef completed = new EventRef("EVT-2", "ORDER_COMPLETED", T2, 3L);
        assertThat(List.of(completed, paid, created).stream().sorted().toList())
                .containsExactly(created, paid, completed);
    }

    @Test
    void requires_identity_and_a_timestamp() {
        assertThatThrownBy(() -> new EventRef(null, "T", T1, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new EventRef("E", null, T1, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new EventRef("E", "T", null, null)).isInstanceOf(NullPointerException.class);
    }
}
