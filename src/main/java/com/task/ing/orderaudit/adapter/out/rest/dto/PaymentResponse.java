package com.task.ing.orderaudit.adapter.out.rest.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentResponse(
        String paymentId,
        String orderId,
        String status,
        BigDecimal amount,
        String currency,
        Instant updatedAt) {
}
