package com.task.ing.orderaudit.integration;

import com.task.ing.orderaudit.support.AbstractIntegrationTest;
import com.task.ing.orderaudit.support.KafkaTestConsumer;
import com.task.ing.orderaudit.support.Payloads;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaIngestionIT extends AbstractIntegrationTest {

    private static final String ORDER_TOPIC = "order-events";
    private static final String PAYMENT_TOPIC = "payment-events";

    @Test
    @DisplayName("an order event is archived and becomes the local state of the order")
    void ingests_an_order_event() {
        String orderId = "ORD-K1";
        kafkaPublisher.publish(ORDER_TOPIC, orderId,
                Payloads.orderEvent("EVT-K1", orderId, "ORDER_CREATED", Payloads.T0));

        awaitAssertion(() -> {
            assertThat(eventCount(orderId, "ORDER")).isEqualTo(1);
            Map<String, Object> order = order(orderId);
            assertThat(order.get("status")).isEqualTo("CREATED");
            assertThat(order.get("customer_id")).isEqualTo("CUST-1");
            assertThat((BigDecimal) order.get("total_amount")).isEqualByComparingTo("150.00");
            assertThat(order.get("currency")).isEqualTo("PLN");
            assertThat(order.get("last_event_id")).isEqualTo("EVT-K1");
        });
    }

    @Test
    @DisplayName("the same message delivered twice is stored once and changes nothing")
    void ignores_a_redelivered_message() {
        String orderId = "ORD-K2";
        String event = Payloads.orderEvent("EVT-K2", orderId, "ORDER_CREATED", Payloads.T0);

        kafkaPublisher.publish(ORDER_TOPIC, orderId, event);
        awaitAssertion(() -> assertThat(eventCount(orderId, "ORDER")).isEqualTo(1));

        kafkaPublisher.publish(ORDER_TOPIC, orderId, event);
        kafkaPublisher.publish(ORDER_TOPIC, orderId, event);

        kafkaPublisher.publish(ORDER_TOPIC, orderId,
                Payloads.orderEvent("EVT-K2-MARKER", orderId, "ORDER_CONFIRMED", Payloads.T1));

        awaitAssertion(() -> assertThat(eventCount(orderId, "ORDER")).isEqualTo(2));
        assertThat(order(orderId).get("status")).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("an event that arrives late is kept as history but does not undo newer state")
    void survives_events_arriving_out_of_order() {
        String orderId = "ORD-K3";

        kafkaPublisher.publish(ORDER_TOPIC, orderId,
                Payloads.orderEvent("EVT-K3-B", orderId, "ORDER_COMPLETED", Payloads.T2));
        awaitAssertion(() -> assertThat(order(orderId).get("status")).isEqualTo("COMPLETED"));

        kafkaPublisher.publish(ORDER_TOPIC, orderId,
                Payloads.orderEvent("EVT-K3-A", orderId, "ORDER_CREATED", Payloads.T0));

        awaitAssertion(() -> assertThat(eventCount(orderId, "ORDER")).isEqualTo(2));
        Map<String, Object> order = order(orderId);
        assertThat(order.get("status")).isEqualTo("COMPLETED");
        assertThat(order.get("last_event_id")).isEqualTo("EVT-K3-B");
    }

    @Test
    @DisplayName("a sequence of changes to one order folds into a single up-to-date projection")
    void folds_repeated_changes_into_one_projection() {
        String orderId = "ORD-K4";

        kafkaPublisher.publish(ORDER_TOPIC, orderId,
                Payloads.orderEventWithItems("EVT-K4-1", orderId, "ORDER_CREATED", Payloads.T0,
                        Payloads.item("P-1", 2, "75.00")));
        kafkaPublisher.publish(ORDER_TOPIC, orderId,
                Payloads.orderEvent("EVT-K4-2", orderId, "ORDER_PAID", Payloads.T1));
        kafkaPublisher.publish(ORDER_TOPIC, orderId,
                Payloads.orderEvent("EVT-K4-3", orderId, "ORDER_COMPLETED", Payloads.T2));

        awaitAssertion(() -> {
            assertThat(eventCount(orderId, "ORDER")).isEqualTo(3);
            assertThat(order(orderId).get("status")).isEqualTo("COMPLETED");
        });
        assertThat(count("orders")).isEqualTo(1);

        assertThat(lines(orderId)).hasSize(1);
    }

    @Test
    @DisplayName("an event carrying items materialises them as order lines")
    void stores_order_lines() {
        String orderId = "ORD-K5";
        kafkaPublisher.publish(ORDER_TOPIC, orderId,
                Payloads.orderEventWithItems("EVT-K5", orderId, "ORDER_CREATED", Payloads.T0,
                        Payloads.item("P-1", 2, "75.00"), Payloads.item("P-2", 1, "10.00")));

        awaitAssertion(() -> assertThat(lines(orderId)).hasSize(2));
        assertThat(lines(orderId))
                .extracting(line -> line.get("product_id"))
                .containsExactlyInAnyOrder("P-1", "P-2");
    }

    @Test
    @DisplayName("a payment event is archived and becomes the local state of the payment")
    void ingests_a_payment_event() {
        String orderId = "ORD-K6";
        kafkaPublisher.publish(PAYMENT_TOPIC, orderId,
                Payloads.paymentEvent("PAY-EVT-K6", "PAY-K6", orderId, "PAYMENT_COMPLETED",
                        "PAID", "150.00", Payloads.T1));

        awaitAssertion(() -> {
            assertThat(eventCount(orderId, "PAYMENT")).isEqualTo(1);
            Map<String, Object> payment = payment(orderId);
            assertThat(payment.get("payment_id")).isEqualTo("PAY-K6");
            assertThat(payment.get("status")).isEqualTo("PAID");
            assertThat((BigDecimal) payment.get("amount")).isEqualByComparingTo("150.00");
        });
    }

    @Test
    @DisplayName("a payment event that arrives late does not roll the payment back")
    void survives_payment_events_arriving_out_of_order() {
        String orderId = "ORD-K7";

        kafkaPublisher.publish(PAYMENT_TOPIC, orderId,
                Payloads.paymentEvent("PAY-EVT-K7-B", "PAY-K7", orderId, "PAYMENT_COMPLETED",
                        "PAID", "150.00", Payloads.T2));
        awaitAssertion(() -> assertThat(payment(orderId).get("status")).isEqualTo("PAID"));

        kafkaPublisher.publish(PAYMENT_TOPIC, orderId,
                Payloads.paymentEvent("PAY-EVT-K7-A", "PAY-K7", orderId, "PAYMENT_INITIATED",
                        "PENDING", "150.00", Payloads.T0));

        awaitAssertion(() -> assertThat(eventCount(orderId, "PAYMENT")).isEqualTo(2));
        assertThat(payment(orderId).get("status")).isEqualTo("PAID");
    }

    @Test
    @DisplayName("a message that cannot be parsed lands on the dead letter topic and the stream keeps flowing")
    void routes_an_unreadable_message_to_the_dead_letter_topic() {
        String orderId = "ORD-K8";
        try (KafkaTestConsumer deadLetters = new KafkaTestConsumer(
                KAFKA.getBootstrapServers(), "order-events.DLT")) {
            kafkaPublisher.publish(ORDER_TOPIC, orderId, "{ this is not valid json ");
            kafkaPublisher.publish(ORDER_TOPIC, orderId,
                    Payloads.orderEvent("EVT-K8", orderId, "ORDER_CREATED", Payloads.T0));

            awaitAssertion(() -> assertThat(order(orderId).get("status")).isEqualTo("CREATED"));

            List<ConsumerRecord<String, String>> rejected = deadLetters.poll(1, Duration.ofSeconds(20));
            assertThat(rejected).hasSize(1);
            assertThat(rejected.getFirst().value()).contains("this is not valid json");
        }
    }

    @Test
    @DisplayName("an event missing a required field is rejected rather than stored half-parsed")
    void rejects_an_event_without_an_event_id() {
        String orderId = "ORD-K9";
        kafkaPublisher.publish(ORDER_TOPIC, orderId,
                """
                {"orderId": "%s", "eventType": "ORDER_CREATED", "status": "CREATED"}
                """.formatted(orderId));
        kafkaPublisher.publish(ORDER_TOPIC, orderId,
                Payloads.orderEvent("EVT-K9", orderId, "ORDER_CREATED", Payloads.T0));

        awaitAssertion(() -> assertThat(eventCount(orderId, "ORDER")).isEqualTo(1));
        assertThat(order(orderId).get("last_event_id")).isEqualTo("EVT-K9");
    }

    @Test
    @DisplayName("a broken payment message lands on the payment dead letter topic")
    void routes_an_unreadable_payment_message_to_its_own_dead_letter_topic() {
        String orderId = "ORD-K10";
        try (KafkaTestConsumer deadLetters = new KafkaTestConsumer(
                KAFKA.getBootstrapServers(), "payment-events.DLT")) {
            kafkaPublisher.publish(PAYMENT_TOPIC, orderId, "not json either");
            kafkaPublisher.publish(PAYMENT_TOPIC, orderId,
                    Payloads.paymentEvent("PAY-EVT-K10", "PAY-K10", orderId, "PAYMENT_COMPLETED",
                            "PAID", "150.00", Payloads.T1));

            awaitAssertion(() -> assertThat(payment(orderId).get("status")).isEqualTo("PAID"));

            assertThat(deadLetters.poll(1, Duration.ofSeconds(20))).hasSize(1);
        }
    }

    @Test
    @DisplayName("a message published without a key is ingested like any other")
    void ingests_a_message_that_carries_no_key() {
        String orderId = "ORD-K11";

        kafkaPublisher.publish(ORDER_TOPIC, null,
                Payloads.orderEvent("EVT-K11", orderId, "ORDER_CREATED", Payloads.T0));

        awaitAssertion(() -> assertThat(order(orderId).get("status")).isEqualTo("CREATED"));
    }

    @Test
    @DisplayName("the two streams meet on the same order")
    void joins_both_streams_onto_one_order() {
        String orderId = "ORD-K12";

        kafkaPublisher.publish(ORDER_TOPIC, orderId,
                Payloads.orderEvent("EVT-K12", orderId, "ORDER_COMPLETED", Payloads.T2));
        kafkaPublisher.publish(PAYMENT_TOPIC, orderId,
                Payloads.paymentEvent("PAY-EVT-K12", "PAY-K12", orderId, "PAYMENT_COMPLETED",
                        "PAID", "150.00", Payloads.T1));

        awaitAssertion(() -> {
            assertThat(order(orderId).get("status")).isEqualTo("COMPLETED");
            assertThat(payment(orderId).get("status")).isEqualTo("PAID");
        });
        assertThat(eventCount(orderId, "ORDER")).isEqualTo(1);
        assertThat(eventCount(orderId, "PAYMENT")).isEqualTo(1);
    }

    private long eventCount(String orderId, String source) {
        Long value = jdbcTemplate.queryForObject(
                "select count(*) from ingested_events where order_id = ? and source = ?",
                Long.class, orderId, source);
        return value == null ? 0 : value;
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
