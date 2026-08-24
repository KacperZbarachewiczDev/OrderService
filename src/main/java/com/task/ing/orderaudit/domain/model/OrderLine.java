package com.task.ing.orderaudit.domain.model;

import java.util.Objects;

public record OrderLine(String productId, int quantity, Money unitPrice) {

    public OrderLine {
        Objects.requireNonNull(productId, "productId must not be null");
        Objects.requireNonNull(unitPrice, "unitPrice must not be null");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive, was: " + quantity);
        }
    }

    public String describe() {
        return productId + " x" + quantity + " @ " + unitPrice.format();
    }
}
