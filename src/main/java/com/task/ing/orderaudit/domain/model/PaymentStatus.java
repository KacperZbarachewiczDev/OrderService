package com.task.ing.orderaudit.domain.model;

import java.util.Locale;

public enum PaymentStatus {

    PENDING,
    AUTHORIZED,
    PAID,
    FAILED,
    REFUNDED,
    UNKNOWN;

    public static PaymentStatus fromExternal(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return UNKNOWN;
        }
    }

    public boolean isSettled() {
        return this == PAID || this == REFUNDED;
    }
}
