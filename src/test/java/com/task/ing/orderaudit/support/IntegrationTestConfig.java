package com.task.ing.orderaudit.support;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;

@TestConfiguration
public class IntegrationTestConfig {

    @Bean
    public NewTopic orderEventsTopic() {
        return TopicBuilder.name("order-events").partitions(2).replicas(1).build();
    }

    @Bean
    public NewTopic paymentEventsTopic() {
        return TopicBuilder.name("payment-events").partitions(2).replicas(1).build();
    }

    @Bean
    public NewTopic orderEventsDeadLetterTopic() {
        return TopicBuilder.name("order-events.DLT").partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic paymentEventsDeadLetterTopic() {
        return TopicBuilder.name("payment-events.DLT").partitions(1).replicas(1).build();
    }
}
