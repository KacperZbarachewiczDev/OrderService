package com.task.ing.orderaudit.adapter.in.kafka;

import com.task.ing.orderaudit.application.port.in.IngestEventUseCase;
import com.task.ing.orderaudit.domain.ingest.IngestOutcome;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@Slf4j
@RequiredArgsConstructor
public class PaymentEventListener {

    private final IngestEventUseCase ingestEvents;
    private final KafkaEventMapper mapper;

    @KafkaListener(
            topics = "${audit.kafka.payment-topic:payment-events}",
            groupId = "${audit.kafka.group-id:order-audit-service}",
            concurrency = "${audit.kafka.concurrency:3}")
    public void onMessage(ConsumerRecord<String, String> record) {
        IngestOutcome outcome = ingestEvents.ingestPaymentEvent(
                mapper.toPaymentCommand(record.value(), Instant.ofEpochMilli(record.timestamp())));
        log.debug("Payment event from {}-{}@{} -> {}",
                record.topic(), record.partition(), record.offset(), outcome);
    }
}
