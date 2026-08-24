package com.task.ing.orderaudit.domain.audit;

import com.task.ing.orderaudit.domain.model.OrderStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderLifecycleTest {

    @Test
    void a_completed_order_must_have_been_created_and_completed() {
        assertThat(OrderLifecycle.requiredEventTypes(OrderStatus.COMPLETED))
                .containsExactly("ORDER_CREATED", "ORDER_COMPLETED");
    }

    @Test
    void a_newly_created_order_needs_only_its_creation_event() {
        assertThat(OrderLifecycle.requiredEventTypes(OrderStatus.CREATED))
                .containsExactly("ORDER_CREATED");
    }

    @Test
    void a_cancelled_order_needs_creation_and_cancellation_but_nothing_in_between() {
        assertThat(OrderLifecycle.requiredEventTypes(OrderStatus.CANCELLED))
                .containsExactly("ORDER_CREATED", "ORDER_CANCELLED")
                .doesNotContain("ORDER_PAID", "ORDER_SHIPPED");
    }

    @Test
    void every_intermediate_status_maps_to_its_own_event() {
        assertThat(OrderLifecycle.requiredEventTypes(OrderStatus.PAID)).contains("ORDER_PAID");
        assertThat(OrderLifecycle.requiredEventTypes(OrderStatus.SHIPPED)).contains("ORDER_SHIPPED");
        assertThat(OrderLifecycle.requiredEventTypes(OrderStatus.CONFIRMED)).contains("ORDER_CONFIRMED");
    }

    @Test
    void makes_no_claim_about_a_status_it_does_not_recognise() {
        assertThat(OrderLifecycle.requiredEventTypes(OrderStatus.UNKNOWN)).isEmpty();
        assertThat(OrderLifecycle.requiredEventTypes(null)).isEmpty();
    }
}
