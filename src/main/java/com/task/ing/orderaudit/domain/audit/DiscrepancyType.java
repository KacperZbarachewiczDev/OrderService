package com.task.ing.orderaudit.domain.audit;

public enum DiscrepancyType {

    ORDER_MISSING_LOCALLY(Severity.CRITICAL),

    ORDER_MISSING_IN_SOURCE(Severity.MAJOR),

    PAYMENT_MISSING_LOCALLY(Severity.CRITICAL),

    PAYMENT_MISSING_IN_SOURCE(Severity.MAJOR),

    ORDER_EVENT_COUNT_MISMATCH(Severity.MAJOR),
    PAYMENT_EVENT_COUNT_MISMATCH(Severity.MAJOR),
    MISSING_ORDER_EVENTS(Severity.CRITICAL),
    MISSING_PAYMENT_EVENTS(Severity.CRITICAL),

    INCOMPLETE_ORDER_HISTORY(Severity.MAJOR),

    ORDER_STATUS_MISMATCH(Severity.CRITICAL),
    ORDER_AMOUNT_MISMATCH(Severity.CRITICAL),
    ORDER_CURRENCY_MISMATCH(Severity.CRITICAL),
    ORDER_CUSTOMER_MISMATCH(Severity.MAJOR),
    ORDER_LINE_MISSING(Severity.MAJOR),
    ORDER_LINE_UNEXPECTED(Severity.MAJOR),
    ORDER_LINE_QUANTITY_MISMATCH(Severity.MAJOR),
    ORDER_LINE_PRICE_MISMATCH(Severity.MAJOR),

    PAYMENT_STATUS_MISMATCH(Severity.CRITICAL),
    PAYMENT_AMOUNT_MISMATCH(Severity.CRITICAL),
    PAYMENT_CURRENCY_MISMATCH(Severity.CRITICAL),
    PAYMENT_IDENTITY_MISMATCH(Severity.MAJOR);

    private final Severity severity;

    DiscrepancyType(Severity severity) {
        this.severity = severity;
    }

    public Severity severity() {
        return severity;
    }
}
