package com.task.ing.orderaudit.domain.notification;

import com.task.ing.orderaudit.domain.audit.Severity;

import java.util.List;
import java.util.Objects;

public record IssueDigest(String orderId, Severity severity, int discrepancyCount, List<String> types) {

    public IssueDigest {
        Objects.requireNonNull(orderId, "orderId must not be null");
        types = types == null ? List.of() : List.copyOf(types);
    }
}
