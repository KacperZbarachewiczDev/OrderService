package com.task.ing.orderaudit.support;

import java.util.Arrays;
import java.util.stream.Collectors;

public final class Payloads {

    public static final String T0 = "2026-08-19T10:00:00Z";
    public static final String T1 = "2026-08-19T11:00:00Z";
    public static final String T2 = "2026-08-19T12:00:00Z";

    private Payloads() {
    }

    public static String order(String orderId, String status, String totalAmount, String... items) {
        return """
                {
                  "orderId": "%s",
                  "customerId": "CUST-1",
                  "status": "%s",
                  "currency": "PLN",
                  "totalAmount": %s,
                  "updatedAt": "%s",
                  "items": [%s]
                }
                """.formatted(orderId, status, totalAmount, T2, String.join(",", items));
    }

    public static String item(String productId, int quantity, String unitPrice) {
        return """
                {"productId": "%s", "quantity": %d, "unitPrice": %s, "currency": "PLN"}
                """.formatted(productId, quantity, unitPrice);
    }

    public static String payment(String paymentId, String orderId, String status, String amount) {
        return """
                {
                  "paymentId": "%s",
                  "orderId": "%s",
                  "status": "%s",
                  "amount": %s,
                  "currency": "PLN",
                  "updatedAt": "%s"
                }
                """.formatted(paymentId, orderId, status, amount, T2);
    }

    public static String orderEvent(String eventId, String orderId, String eventType, String occurredAt) {
        return """
                {
                  "eventId": "%s",
                  "orderId": "%s",
                  "eventType": "%s",
                  "occurredAt": "%s",
                  "customerId": "CUST-1",
                  "status": "%s",
                  "currency": "PLN",
                  "totalAmount": 150.00
                }
                """.formatted(eventId, orderId, eventType, occurredAt, statusFor(eventType));
    }

    public static String orderEventWithItems(
            String eventId, String orderId, String eventType, String occurredAt, String... items) {
        return """
                {
                  "eventId": "%s",
                  "orderId": "%s",
                  "eventType": "%s",
                  "occurredAt": "%s",
                  "customerId": "CUST-1",
                  "status": "%s",
                  "currency": "PLN",
                  "totalAmount": 150.00,
                  "items": [%s]
                }
                """.formatted(eventId, orderId, eventType, occurredAt, statusFor(eventType),
                String.join(",", items));
    }

    public static String paymentEvent(
            String eventId, String paymentId, String orderId, String eventType,
            String status, String amount, String occurredAt) {
        return """
                {
                  "eventId": "%s",
                  "paymentId": "%s",
                  "orderId": "%s",
                  "eventType": "%s",
                  "status": "%s",
                  "amount": %s,
                  "currency": "PLN",
                  "occurredAt": "%s"
                }
                """.formatted(eventId, paymentId, orderId, eventType, status, amount, occurredAt);
    }

    public static String jsonArray(String... elements) {
        return Arrays.stream(elements).collect(Collectors.joining(",", "[", "]"));
    }

    private static String statusFor(String eventType) {
        return switch (eventType) {
            case "ORDER_CREATED" -> "CREATED";
            case "ORDER_CONFIRMED" -> "CONFIRMED";
            case "ORDER_PAID" -> "PAID";
            case "ORDER_SHIPPED" -> "SHIPPED";
            case "ORDER_COMPLETED" -> "COMPLETED";
            case "ORDER_CANCELLED" -> "CANCELLED";
            default -> "UNKNOWN";
        };
    }
}
