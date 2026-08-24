package com.task.ing.orderaudit.domain.ingest;

import com.task.ing.orderaudit.domain.model.EventRef;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class EventOrderingTest {

    private static final Instant EARLY = Instant.parse("2026-08-19T10:00:00Z");
    private static final Instant LATE = Instant.parse("2026-08-19T12:00:00Z");

    @Test
    void applies_the_first_event_an_order_ever_receives() {
        assertThat(EventOrdering.shouldApply(ref("EVT-1", EARLY), null)).isTrue();
    }

    @Test
    void applies_an_event_newer_than_the_current_state() {
        assertThat(EventOrdering.shouldApply(ref("EVT-2", LATE), ref("EVT-1", EARLY))).isTrue();
    }

    @Test
    void refuses_an_event_older_than_the_current_state() {
        assertThat(EventOrdering.shouldApply(ref("EVT-1", EARLY), ref("EVT-2", LATE))).isFalse();
    }

    @Test
    void refuses_re_applying_the_very_same_event() {
        EventRef event = ref("EVT-1", EARLY);
        assertThat(EventOrdering.shouldApply(event, event)).isFalse();
    }

    @Test
    void refuses_a_null_incoming_event() {
        assertThat(EventOrdering.shouldApply(null, ref("EVT-1", EARLY))).isFalse();
        assertThat(EventOrdering.shouldApply(null, null)).isFalse();
    }

    private EventRef ref(String id, Instant at) {
        return EventRef.of(id, "ORDER_UPDATED", at);
    }
}
