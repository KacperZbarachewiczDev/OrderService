package com.task.ing.orderaudit.adapter.out.rest.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderResponse(
        String orderId,
        String customerId,
        String status,
        String currency,
        BigDecimal totalAmount,
        Instant updatedAt,
        List<Item> items) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(String productId, Integer quantity, BigDecimal unitPrice, String currency) {
    }
}
