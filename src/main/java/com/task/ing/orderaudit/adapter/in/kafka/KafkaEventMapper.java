package com.task.ing.orderaudit.adapter.in.kafka;

import com.task.ing.orderaudit.application.port.in.OrderEventCommand;
import com.task.ing.orderaudit.application.port.in.PaymentEventCommand;
import com.task.ing.orderaudit.domain.model.EventOrigin;
import com.task.ing.orderaudit.domain.model.Money;
import com.task.ing.orderaudit.domain.model.OrderLine;
import com.task.ing.orderaudit.domain.model.OrderStatus;
import com.task.ing.orderaudit.domain.model.PaymentStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class KafkaEventMapper {

    private final ObjectMapper objectMapper;

    public OrderEventCommand toOrderCommand(String payload, Instant recordTimestamp) {
        JsonNode node = read(payload);
        String eventId = required(node, "eventId");
        String orderId = required(node, "orderId");
        String eventType = required(node, "eventType");
        String currency = text(node, "currency");

        return new OrderEventCommand(
                eventId,
                orderId,
                eventType,
                text(node, "customerId"),
                node.hasNonNull("status") ? OrderStatus.fromExternal(text(node, "status")) : null,
                money(decimal(node, "totalAmount"), currency),
                lines(node, currency),
                occurredAt(node, recordTimestamp),
                node.hasNonNull("sequenceNo") ? node.get("sequenceNo").asLong() : null,
                node.toString(),
                EventOrigin.KAFKA);
    }

    public PaymentEventCommand toPaymentCommand(String payload, Instant recordTimestamp) {
        JsonNode node = read(payload);
        String eventId = required(node, "eventId");
        String paymentId = required(node, "paymentId");
        String orderId = required(node, "orderId");
        String eventType = required(node, "eventType");
        String currency = text(node, "currency");

        return new PaymentEventCommand(
                eventId,
                paymentId,
                orderId,
                eventType,
                node.hasNonNull("status") ? PaymentStatus.fromExternal(text(node, "status")) : null,
                money(decimal(node, "amount"), currency),
                occurredAt(node, recordTimestamp),
                node.hasNonNull("sequenceNo") ? node.get("sequenceNo").asLong() : null,
                node.toString(),
                EventOrigin.KAFKA);
    }

    private JsonNode read(String payload) {
        if (payload == null || payload.isBlank()) {
            throw new InvalidEventException("empty message body");
        }
        try {
            JsonNode node = objectMapper.readTree(payload);
            if (!node.isObject()) {
                throw new InvalidEventException("message body is not a JSON object");
            }
            return node;
        } catch (JacksonException e) {
            throw new InvalidEventException("message body is not valid JSON", e);
        }
    }

    private List<OrderLine> lines(JsonNode node, String orderCurrency) {
        JsonNode items = node.get("items");
        if (items == null || !items.isArray()) {
            return null;
        }
        List<OrderLine> lines = new ArrayList<>();
        for (JsonNode item : items) {
            String productId = text(item, "productId");
            BigDecimal unitPrice = decimal(item, "unitPrice");
            if (productId == null || unitPrice == null || !item.hasNonNull("quantity")) {
                throw new InvalidEventException("order item is missing productId, quantity or unitPrice");
            }
            String currency = item.hasNonNull("currency") ? text(item, "currency") : orderCurrency;
            if (currency == null) {
                throw new InvalidEventException("order item " + productId + " has no currency");
            }
            lines.add(new OrderLine(productId, item.get("quantity").asInt(), new Money(unitPrice, currency)));
        }
        return lines;
    }

    private Instant occurredAt(JsonNode node, Instant recordTimestamp) {
        String raw = text(node, "occurredAt");
        if (raw == null) {
            return recordTimestamp;
        }
        try {
            return Instant.parse(raw);
        } catch (RuntimeException e) {
            throw new InvalidEventException("unreadable occurredAt: " + raw, e);
        }
    }

    private Money money(BigDecimal amount, String currency) {
        if (amount == null || currency == null) {
            return null;
        }
        return new Money(amount, currency);
    }

    private BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        try {
            return new BigDecimal(value.asString());
        } catch (NumberFormatException e) {
            throw new InvalidEventException("field " + field + " is not a number: " + value.asString(), e);
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private String required(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null || value.isBlank()) {
            throw new InvalidEventException("required field '" + field + "' is missing");
        }
        return value;
    }
}
