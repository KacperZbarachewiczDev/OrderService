package com.task.ing.orderaudit.domain.audit;

import java.util.Collection;
import java.util.Objects;

public record Discrepancy(DiscrepancyType type, String field, String expected, String actual) {

    public Discrepancy {
        Objects.requireNonNull(type, "type must not be null");
    }

    public static Discrepancy of(DiscrepancyType type, String field, String expected, String actual) {
        return new Discrepancy(type, field, expected, actual);
    }

    public static Discrepancy of(DiscrepancyType type, String field) {
        return new Discrepancy(type, field, null, null);
    }

    public Severity severity() {
        return type.severity();
    }

    public static Severity highestSeverity(Collection<Discrepancy> discrepancies) {
        Severity highest = null;
        for (Discrepancy discrepancy : discrepancies) {
            highest = Severity.max(highest, discrepancy.severity());
        }
        return highest;
    }

    public String describe() {
        StringBuilder text = new StringBuilder(type.name());
        if (field != null) {
            text.append(" [").append(field).append(']');
        }
        if (expected != null || actual != null) {
            text.append(": source=").append(expected).append(", local=").append(actual);
        }
        return text.toString();
    }
}
