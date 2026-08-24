package com.task.ing.orderaudit.domain.model;

import java.util.Locale;

public enum OrderStatus {

    CREATED,
    CONFIRMED,
    PAID,
    SHIPPED,
    COMPLETED,
    CANCELLED,
    UNKNOWN;

    public static OrderStatus fromExternal(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return UNKNOWN;
        }
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED;
    }
}
