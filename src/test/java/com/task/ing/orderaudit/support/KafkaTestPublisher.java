package com.task.ing.orderaudit.support;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.Map;
import java.util.concurrent.ExecutionException;

public class KafkaTestPublisher implements AutoCloseable {

    private final Producer<String, String> producer;

    public KafkaTestPublisher(String bootstrapServers) {
        this.producer = new KafkaProducer<>(Map.of(
                "bootstrap.servers", bootstrapServers,
                "key.serializer", StringSerializer.class.getName(),
                "value.serializer", StringSerializer.class.getName(),
                "acks", "all",
                "linger.ms", "0"));
    }

    public void publish(String topic, String key, String payload) {
        try {
            producer.send(new ProducerRecord<>(topic, key, payload)).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while publishing to " + topic, e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("could not publish to " + topic, e);
        }
    }

    @Override
    public void close() {
        producer.close();
    }
}
