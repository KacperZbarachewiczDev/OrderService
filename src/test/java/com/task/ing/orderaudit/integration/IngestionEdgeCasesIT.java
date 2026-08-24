package com.task.ing.orderaudit.integration;

import com.task.ing.orderaudit.adapter.in.kafka.InvalidEventException;
import com.task.ing.orderaudit.domain.ingest.IngestOutcome;
import com.task.ing.orderaudit.support.AbstractIntegrationTest;
import com.task.ing.orderaudit.support.Payloads;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IngestionEdgeCasesIT extends AbstractIntegrationTest {

    @Test
    @DisplayName("a status-only event does not wipe the total this service already knew")
    void a_partial_event_leaves_untouched_fields_alone() {
        String orderId = "ORD-E1";
        givenReceivedOrderEvent(Payloads.orderEvent("EVT-E1-1", orderId, "ORDER_CREATED", Payloads.T0));

        givenReceivedOrderEvent("""
                {"eventId": "EVT-E1-2", "orderId": "%s", "eventType": "ORDER_SHIPPED",
                 "status": "SHIPPED", "occurredAt": "%s"}
                """.formatted(orderId, Payloads.T1));

        Map<String, Object> order = order(orderId);
        assertThat(order.get("status")).isEqualTo("SHIPPED");
        assertThat((BigDecimal) order.get("total_amount")).isEqualByComparingTo("150.00");
        assertThat(order.get("customer_id")).isEqualTo("CUST-1");
    }

    @Test
    @DisplayName("an event that does list items replaces the ones held before")
    void an_event_with_items_replaces_the_previous_ones() {
        String orderId = "ORD-E2";
        givenReceivedOrderEvent(Payloads.orderEventWithItems("EVT-E2-1", orderId, "ORDER_CREATED",
                Payloads.T0, Payloads.item("P-1", 2, "75.00")));

        givenReceivedOrderEvent(Payloads.orderEventWithItems("EVT-E2-2", orderId, "ORDER_CONFIRMED",
                Payloads.T1, Payloads.item("P-2", 1, "150.00")));

        assertThat(lines(orderId)).extracting(line -> line.get("product_id")).containsExactly("P-2");
    }

    @Test
    @DisplayName("an item list that came back empty removes the items")
    void an_explicitly_empty_item_list_clears_the_lines() {
        String orderId = "ORD-E3";
        givenReceivedOrderEvent(Payloads.orderEventWithItems("EVT-E3-1", orderId, "ORDER_CREATED",
                Payloads.T0, Payloads.item("P-1", 2, "75.00")));

        givenReceivedOrderEvent(Payloads.orderEventWithItems("EVT-E3-2", orderId, "ORDER_CONFIRMED",
                Payloads.T1));

        assertThat(lines(orderId)).isEmpty();
    }

    @Test
    @DisplayName("a status this service has never heard of is stored rather than rejected")
    void an_unknown_status_does_not_stop_ingestion() {
        String orderId = "ORD-E4";

        givenReceivedOrderEvent("""
                {"eventId": "EVT-E4", "orderId": "%s", "eventType": "ORDER_HELD_AT_CUSTOMS",
                 "status": "HELD_AT_CUSTOMS", "occurredAt": "%s"}
                """.formatted(orderId, Payloads.T0));

        assertThat(order(orderId).get("status")).isEqualTo("UNKNOWN");
        assertThat(count("ingested_events")).isEqualTo(1);
    }

    @Test
    @DisplayName("an order that changes currency keeps both the amount and the currency in step")
    void a_currency_change_is_carried_through() {
        String orderId = "ORD-E5";
        givenReceivedOrderEvent(Payloads.orderEvent("EVT-E5-1", orderId, "ORDER_CREATED", Payloads.T0));

        givenReceivedOrderEvent("""
                {"eventId": "EVT-E5-2", "orderId": "%s", "eventType": "ORDER_UPDATED",
                 "currency": "EUR", "totalAmount": 35.00, "occurredAt": "%s"}
                """.formatted(orderId, Payloads.T1));

        Map<String, Object> order = order(orderId);
        assertThat(order.get("currency")).isEqualTo("EUR");
        assertThat((BigDecimal) order.get("total_amount")).isEqualByComparingTo("35.00");
    }

    @Test
    @DisplayName("two events sharing a timestamp are ordered by their sequence number")
    void breaks_a_timestamp_tie_with_the_sequence_number() {
        String orderId = "ORD-E6";

        givenReceivedOrderEvent(sequencedEvent("EVT-E6-2", orderId, "ORDER_COMPLETED", "COMPLETED", 2));
        givenReceivedOrderEvent(sequencedEvent("EVT-E6-1", orderId, "ORDER_CREATED", "CREATED", 1));

        assertThat(order(orderId).get("status")).isEqualTo("COMPLETED");
        assertThat(order(orderId).get("last_event_id")).isEqualTo("EVT-E6-2");
        assertThat(count("ingested_events")).isEqualTo(2);
    }

    @Test
    @DisplayName("a higher sequence number on the same timestamp does advance the state")
    void applies_a_later_sequence_number() {
        String orderId = "ORD-E7";

        givenReceivedOrderEvent(sequencedEvent("EVT-E7-1", orderId, "ORDER_CREATED", "CREATED", 1));
        givenReceivedOrderEvent(sequencedEvent("EVT-E7-2", orderId, "ORDER_COMPLETED", "COMPLETED", 2));

        assertThat(order(orderId).get("status")).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("an event without a timestamp of its own falls back to the broker's")
    void falls_back_to_the_broker_timestamp() {
        String orderId = "ORD-E8";
        Instant brokerTime = Instant.parse("2026-08-19T09:30:00Z");

        ingestEvents.ingestOrderEvent(kafkaEventMapper.toOrderCommand("""
                {"eventId": "EVT-E8", "orderId": "%s", "eventType": "ORDER_CREATED", "status": "CREATED"}
                """.formatted(orderId), brokerTime));

        assertThat(jdbcTemplate.queryForObject(
                "select occurred_at from ingested_events where event_id = 'EVT-E8'", Instant.class))
                .isEqualTo(brokerTime);
    }

    @Test
    @DisplayName("the outcome of ingestion is reported so a listener can log what happened")
    void reports_what_it_did_with_each_event() {
        String orderId = "ORD-E9";
        String event = Payloads.orderEvent("EVT-E9", orderId, "ORDER_COMPLETED", Payloads.T2);

        assertThat(ingest(event)).isEqualTo(IngestOutcome.APPLIED);
        assertThat(ingest(event)).isEqualTo(IngestOutcome.DUPLICATE);
        assertThat(ingest(Payloads.orderEvent("EVT-E9-OLD", orderId, "ORDER_CREATED", Payloads.T0)))
                .isEqualTo(IngestOutcome.STORED_OUT_OF_ORDER);
    }

    @Test
    @DisplayName("a payment that is later refunded ends up in the refunded state")
    void follows_a_payment_through_to_a_refund() {
        String orderId = "ORD-E10";

        givenReceivedPaymentEvent(Payloads.paymentEvent("PAY-EVT-E10-1", "PAY-E10", orderId,
                "PAYMENT_COMPLETED", "PAID", "150.00", Payloads.T0));
        givenReceivedPaymentEvent(Payloads.paymentEvent("PAY-EVT-E10-2", "PAY-E10", orderId,
                "PAYMENT_REFUNDED", "REFUNDED", "150.00", Payloads.T2));

        assertThat(payment(orderId).get("status")).isEqualTo("REFUNDED");
        assertThat(count("ingested_events")).isEqualTo(2);
    }

    @Test
    @DisplayName("an event whose payload is not an object is rejected")
    void rejects_a_payload_that_is_not_an_object() {
        assertThatThrownBy(() -> ingest("[1, 2, 3]"))
                .isInstanceOf(InvalidEventException.class)
                .hasMessageContaining("not a JSON object");
    }

    @Test
    @DisplayName("an empty message is rejected")
    void rejects_an_empty_message() {
        assertThatThrownBy(() -> ingest("   "))
                .isInstanceOf(InvalidEventException.class)
                .hasMessageContaining("empty message body");
    }

    @Test
    @DisplayName("an item without a price is rejected rather than stored as zero")
    void rejects_an_item_without_a_price() {
        assertThatThrownBy(() -> ingest("""
                {"eventId": "EVT-BAD", "orderId": "ORD-E11", "eventType": "ORDER_CREATED",
                 "occurredAt": "%s", "currency": "PLN",
                 "items": [{"productId": "P-1", "quantity": 2}]}
                """.formatted(Payloads.T0)))
                .isInstanceOf(InvalidEventException.class)
                .hasMessageContaining("unitPrice");
    }

    @Test
    @DisplayName("an amount that is not a number is rejected")
    void rejects_an_unreadable_amount() {
        assertThatThrownBy(() -> ingest("""
                {"eventId": "EVT-BAD2", "orderId": "ORD-E12", "eventType": "ORDER_CREATED",
                 "occurredAt": "%s", "currency": "PLN", "totalAmount": "a lot"}
                """.formatted(Payloads.T0)))
                .isInstanceOf(InvalidEventException.class)
                .hasMessageContaining("not a number");
    }

    @Test
    @DisplayName("a payment event without its payment identifier is rejected")
    void rejects_a_payment_event_without_a_payment_id() {
        assertThatThrownBy(() -> ingestEvents.ingestPaymentEvent(kafkaEventMapper.toPaymentCommand("""
                {"eventId": "PAY-EVT-BAD", "orderId": "ORD-E13", "eventType": "PAYMENT_COMPLETED"}
                """, Instant.now())))
                .isInstanceOf(InvalidEventException.class)
                .hasMessageContaining("paymentId");
    }

    @Test
    @DisplayName("the original message is archived so a field this service ignores is not lost")
    void keeps_the_original_payload() {
        String orderId = "ORD-E14";

        givenReceivedOrderEvent("""
                {"eventId": "EVT-E14", "orderId": "%s", "eventType": "ORDER_CREATED",
                 "status": "CREATED", "occurredAt": "%s", "warehouseCode": "WH-7"}
                """.formatted(orderId, Payloads.T0));

        assertThat(jdbcTemplate.queryForObject(
                "select payload::text from ingested_events where event_id = 'EVT-E14'", String.class))
                .contains("WH-7");
    }

    @Test
    @DisplayName("a payment can arrive before the order it belongs to")
    void accepts_a_payment_for_an_order_it_has_never_seen() {
        String orderId = "ORD-E15";

        givenReceivedPaymentEvent(Payloads.paymentEvent("PAY-EVT-E15", "PAY-E15", orderId,
                "PAYMENT_COMPLETED", "PAID", "150.00", Payloads.T0));

        assertThat(payment(orderId).get("status")).isEqualTo("PAID");
        assertThat(jdbcTemplate.queryForList(
                "select order_id from orders where order_id = ?", String.class, orderId)).isEmpty();
    }

    private IngestOutcome ingest(String json) {
        return ingestEvents.ingestOrderEvent(kafkaEventMapper.toOrderCommand(json, Instant.now()));
    }

    private String sequencedEvent(
            String eventId, String orderId, String eventType, String status, long sequenceNo) {
        return """
                {"eventId": "%s", "orderId": "%s", "eventType": "%s", "status": "%s",
                 "occurredAt": "%s", "sequenceNo": %d, "currency": "PLN", "totalAmount": 150.00}
                """.formatted(eventId, orderId, eventType, status, Payloads.T0, sequenceNo);
    }

    private Map<String, Object> order(String orderId) {
        return jdbcTemplate.queryForMap("select * from orders where order_id = ?", orderId);
    }

    private Map<String, Object> payment(String orderId) {
        return jdbcTemplate.queryForMap("select * from payments where order_id = ?", orderId);
    }

    private List<Map<String, Object>> lines(String orderId) {
        return jdbcTemplate.queryForList("select * from order_lines where order_id = ?", orderId);
    }
}
