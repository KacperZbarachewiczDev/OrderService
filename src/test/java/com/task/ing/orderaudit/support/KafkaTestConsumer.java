package com.task.ing.orderaudit.support;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class KafkaTestConsumer implements AutoCloseable {

    private final KafkaConsumer<String, String> consumer;

    public KafkaTestConsumer(String bootstrapServers, String topic) {
        this.consumer = new KafkaConsumer<>(Map.of(
                "bootstrap.servers", bootstrapServers,
                "key.deserializer", StringDeserializer.class.getName(),
                "value.deserializer", StringDeserializer.class.getName(),
                "group.id", "test-" + UUID.randomUUID(),
                "auto.offset.reset", "earliest",
                "enable.auto.commit", "false"));
        consumer.subscribe(List.of(topic));
    }

    public List<ConsumerRecord<String, String>> poll(int expected, Duration timeout) {
        List<ConsumerRecord<String, String>> collected = new ArrayList<>();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (collected.size() < expected && System.nanoTime() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            records.forEach(collected::add);
        }
        return collected;
    }

    @Override
    public void close() {
        consumer.close();
    }
}
