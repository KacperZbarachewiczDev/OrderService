package com.task.ing.orderaudit.adapter.out.rest;

import com.task.ing.orderaudit.application.port.out.SourceEvent;
import com.task.ing.orderaudit.application.port.out.SourceUnavailableException;
import com.task.ing.orderaudit.domain.model.EventRef;
import lombok.RequiredArgsConstructor;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@RequiredArgsConstructor
public class SourceEventParser {

    private final ObjectMapper objectMapper;
    private final String aggregateIdField;
    private final String systemName;

    public List<SourceEvent> parse(String orderId, String json) {
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (JacksonException e) {
            throw new SourceUnavailableException(
                    systemName + " returned unparseable event history for " + orderId, e);
        }
        if (!root.isArray()) {
            throw new SourceUnavailableException(
                    systemName + " returned a non-array event history for " + orderId);
        }

        List<SourceEvent> events = new ArrayList<>();
        for (JsonNode node : root) {
            events.add(toSourceEvent(orderId, node));
        }
        return List.copyOf(events);
    }

    private SourceEvent toSourceEvent(String orderId, JsonNode node) {
        String eventId = text(node, "eventId");
        String eventType = text(node, "eventType");
        if (eventId == null || eventType == null) {
            throw new SourceUnavailableException(
                    systemName + " returned an event without eventId or eventType for " + orderId);
        }
        Instant occurredAt = occurredAt(node);
        Long sequenceNo = node.hasNonNull("sequenceNo") ? node.get("sequenceNo").asLong() : null;
        String aggregateId = text(node, aggregateIdField);
        return new SourceEvent(
                new EventRef(eventId, eventType, occurredAt, sequenceNo),
                orderId,
                aggregateId != null ? aggregateId : orderId,
                node.toString());
    }

    private Instant occurredAt(JsonNode node) {
        String raw = text(node, "occurredAt");
        if (raw == null) {
            raw = text(node, "timestamp");
        }
        if (raw == null) {
            return Instant.EPOCH;
        }
        try {
            return Instant.parse(raw);
        } catch (RuntimeException e) {
            throw new SourceUnavailableException(
                    systemName + " returned an unreadable occurredAt: " + raw, e);
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }
}
