package com.task.ing.orderaudit.adapter.out.rest;

import com.task.ing.orderaudit.adapter.out.rest.dto.EventCountResponse;
import com.task.ing.orderaudit.adapter.out.rest.dto.ModifiedOrdersResponse;
import com.task.ing.orderaudit.adapter.out.rest.dto.OrderResponse;
import com.task.ing.orderaudit.application.port.out.OrderSourceClient;
import com.task.ing.orderaudit.application.port.out.SourceEvent;
import com.task.ing.orderaudit.domain.model.Money;
import com.task.ing.orderaudit.domain.model.OrderLine;
import com.task.ing.orderaudit.domain.model.OrderSnapshot;
import com.task.ing.orderaudit.domain.model.OrderStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class OrderRestClientAdapter implements OrderSourceClient {

    private static final int MAX_PAGES = 10_000;

    private final SourceHttpGateway gateway;
    private final SourceEventParser eventParser;

    @Override
    public Optional<OrderSnapshot> fetchOrder(String orderId) {
        return gateway.getOptional("/orders/{orderId}", OrderResponse.class, orderId)
                .map(this::toSnapshot);
    }

    @Override
    public List<SourceEvent> fetchEvents(String orderId) {
        String body = gateway.getRequired("/orders/{orderId}/events", String.class, orderId);
        return eventParser.parse(orderId, body);
    }

    @Override
    public long countEvents(String orderId) {
        return gateway.getOptional("/orders/{orderId}/events/count", EventCountResponse.class, orderId)
                .map(EventCountResponse::count)
                .orElse(0L);
    }

    @Override
    public List<String> findOrderIdsModifiedSince(Instant since) {
        Set<String> ids = new LinkedHashSet<>();
        String pageToken = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            ModifiedOrdersResponse response = pageToken == null
                    ? gateway.getRequired("/orders?modifiedSince={since}",
                    ModifiedOrdersResponse.class, since.toString())
                    : gateway.getRequired("/orders?modifiedSince={since}&pageToken={token}",
                    ModifiedOrdersResponse.class, since.toString(), pageToken);
            ids.addAll(response.orderIds());
            pageToken = response.nextPageToken();
            if (pageToken == null || pageToken.isBlank()) {
                break;
            }
        }
        return List.copyOf(ids);
    }

    private OrderSnapshot toSnapshot(OrderResponse response) {
        List<OrderLine> lines = new ArrayList<>();
        if (response.items() != null) {
            for (OrderResponse.Item item : response.items()) {
                if (item.productId() == null || item.quantity() == null || item.unitPrice() == null) {
                    continue;
                }
                String currency = item.currency() != null ? item.currency() : response.currency();
                lines.add(new OrderLine(
                        item.productId(), item.quantity(), new Money(item.unitPrice(), currency)));
            }
        }
        Money total = response.totalAmount() == null || response.currency() == null
                ? null
                : new Money(response.totalAmount(), response.currency());
        return new OrderSnapshot(
                response.orderId(),
                response.customerId(),
                OrderStatus.fromExternal(response.status()),
                total,
                lines,
                response.updatedAt());
    }
}
