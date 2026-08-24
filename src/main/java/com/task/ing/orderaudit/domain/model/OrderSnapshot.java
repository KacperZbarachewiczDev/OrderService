package com.task.ing.orderaudit.domain.model;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record OrderSnapshot(
        String orderId,
        String customerId,
        OrderStatus status,
        Money totalAmount,
        List<OrderLine> lines,
        Instant updatedAt) {
    public OrderSnapshot {
        Objects.requireNonNull(orderId, "orderId must not be null");
        status = status == null ? OrderStatus.UNKNOWN : status;
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    public Map<String, OrderLine> linesByProduct() {
        Map<String, OrderLine> byProduct = new LinkedHashMap<>();
        for (OrderLine line : lines) {
            byProduct.put(line.productId(), line);
        }
        return byProduct;
    }

    public boolean hasLines() {
        return !lines.isEmpty();
    }
}
