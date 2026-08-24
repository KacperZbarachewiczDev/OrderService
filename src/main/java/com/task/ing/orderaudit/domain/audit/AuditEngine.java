package com.task.ing.orderaudit.domain.audit;

import com.task.ing.orderaudit.domain.model.EventRef;
import com.task.ing.orderaudit.domain.model.Money;
import com.task.ing.orderaudit.domain.model.OrderLine;
import com.task.ing.orderaudit.domain.model.OrderSnapshot;
import com.task.ing.orderaudit.domain.model.OrderStatus;
import com.task.ing.orderaudit.domain.model.PaymentSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class AuditEngine {

    private static final int MAX_LISTED_IDS = 20;

    public List<Discrepancy> compare(LocalOrderView local, SourceOrderView source) {
        Objects.requireNonNull(local, "local view must not be null");
        Objects.requireNonNull(source, "source view must not be null");

        List<Discrepancy> findings = new ArrayList<>();
        compareOrder(local, source, findings);
        comparePayment(local, source, findings);
        compareOrderEvents(local, source, findings);
        comparePaymentEvents(local, source, findings);
        compareHistoryCompleteness(local, source, findings);
        return List.copyOf(findings);
    }

    private void compareOrder(LocalOrderView local, SourceOrderView source, List<Discrepancy> findings) {
        Optional<OrderSnapshot> remote = source.order();
        Optional<OrderSnapshot> mine = local.order();

        if (remote.isPresent() && mine.isEmpty()) {
            findings.add(Discrepancy.of(
                    DiscrepancyType.ORDER_MISSING_LOCALLY, "orderId", source.orderId(), null));
            return;
        }
        if (remote.isEmpty() && mine.isPresent()) {
            findings.add(Discrepancy.of(
                    DiscrepancyType.ORDER_MISSING_IN_SOURCE, "orderId", null, source.orderId()));
            return;
        }
        if (remote.isEmpty()) {
            return;
        }

        OrderSnapshot expected = remote.get();
        OrderSnapshot actual = mine.get();

        if (expected.status() != actual.status()) {
            findings.add(Discrepancy.of(DiscrepancyType.ORDER_STATUS_MISMATCH, "status",
                    expected.status().name(), actual.status().name()));
        }
        if (expected.customerId() != null && !expected.customerId().equals(actual.customerId())) {
            findings.add(Discrepancy.of(DiscrepancyType.ORDER_CUSTOMER_MISMATCH, "customerId",
                    expected.customerId(), actual.customerId()));
        }
        compareMoney(expected.totalAmount(), actual.totalAmount(), "totalAmount",
                DiscrepancyType.ORDER_AMOUNT_MISMATCH, DiscrepancyType.ORDER_CURRENCY_MISMATCH, findings);
        compareLines(expected, actual, findings);
    }

    private void compareLines(OrderSnapshot expected, OrderSnapshot actual, List<Discrepancy> findings) {
        if (!expected.hasLines()) {
            return;
        }
        Map<String, OrderLine> expectedLines = expected.linesByProduct();
        Map<String, OrderLine> actualLines = actual.linesByProduct();

        for (Map.Entry<String, OrderLine> entry : expectedLines.entrySet()) {
            OrderLine expectedLine = entry.getValue();
            OrderLine actualLine = actualLines.get(entry.getKey());
            if (actualLine == null) {
                findings.add(Discrepancy.of(DiscrepancyType.ORDER_LINE_MISSING, entry.getKey(),
                        expectedLine.describe(), null));
                continue;
            }
            if (expectedLine.quantity() != actualLine.quantity()) {
                findings.add(Discrepancy.of(DiscrepancyType.ORDER_LINE_QUANTITY_MISMATCH, entry.getKey(),
                        String.valueOf(expectedLine.quantity()), String.valueOf(actualLine.quantity())));
            }
            if (!expectedLine.unitPrice().equals(actualLine.unitPrice())) {
                findings.add(Discrepancy.of(DiscrepancyType.ORDER_LINE_PRICE_MISMATCH, entry.getKey(),
                        expectedLine.unitPrice().format(), actualLine.unitPrice().format()));
            }
        }
        for (Map.Entry<String, OrderLine> entry : actualLines.entrySet()) {
            if (!expectedLines.containsKey(entry.getKey())) {
                findings.add(Discrepancy.of(DiscrepancyType.ORDER_LINE_UNEXPECTED, entry.getKey(),
                        null, entry.getValue().describe()));
            }
        }
    }

    private void comparePayment(LocalOrderView local, SourceOrderView source, List<Discrepancy> findings) {
        Optional<PaymentSnapshot> remote = source.payment();
        Optional<PaymentSnapshot> mine = local.payment();

        if (remote.isPresent() && mine.isEmpty()) {
            findings.add(Discrepancy.of(DiscrepancyType.PAYMENT_MISSING_LOCALLY, "paymentId",
                    remote.get().paymentId(), null));
            return;
        }
        if (remote.isEmpty() && mine.isPresent()) {
            findings.add(Discrepancy.of(DiscrepancyType.PAYMENT_MISSING_IN_SOURCE, "paymentId",
                    null, mine.get().paymentId()));
            return;
        }
        if (remote.isEmpty()) {
            return;
        }

        PaymentSnapshot expected = remote.get();
        PaymentSnapshot actual = mine.get();

        if (!expected.paymentId().equals(actual.paymentId())) {
            findings.add(Discrepancy.of(DiscrepancyType.PAYMENT_IDENTITY_MISMATCH, "paymentId",
                    expected.paymentId(), actual.paymentId()));
        }
        if (expected.status() != actual.status()) {
            findings.add(Discrepancy.of(DiscrepancyType.PAYMENT_STATUS_MISMATCH, "status",
                    expected.status().name(), actual.status().name()));
        }
        compareMoney(expected.amount(), actual.amount(), "amount",
                DiscrepancyType.PAYMENT_AMOUNT_MISMATCH, DiscrepancyType.PAYMENT_CURRENCY_MISMATCH, findings);
    }

    private void compareMoney(
            Money expected,
            Money actual,
            String field,
            DiscrepancyType amountMismatch,
            DiscrepancyType currencyMismatch,
            List<Discrepancy> findings) {
        if (expected == null && actual == null) {
            return;
        }
        if (expected == null || actual == null) {
            findings.add(Discrepancy.of(amountMismatch, field,
                    expected == null ? null : expected.format(),
                    actual == null ? null : actual.format()));
            return;
        }
        if (!expected.sameCurrencyAs(actual)) {
            findings.add(Discrepancy.of(currencyMismatch, field, expected.currency(), actual.currency()));
        }
        if (!expected.sameAmountAs(actual)) {
            findings.add(Discrepancy.of(amountMismatch, field, expected.format(), actual.format()));
        }
    }

    private void compareOrderEvents(LocalOrderView local, SourceOrderView source, List<Discrepancy> findings) {
        compareEventStreams(local.orderEvents(), source.orderEvents(), source.orderEventCount(),
                source.eventDetailsAvailable(),
                DiscrepancyType.ORDER_EVENT_COUNT_MISMATCH, DiscrepancyType.MISSING_ORDER_EVENTS, findings);
    }

    private void comparePaymentEvents(LocalOrderView local, SourceOrderView source, List<Discrepancy> findings) {
        compareEventStreams(local.paymentEvents(), source.paymentEvents(), source.paymentEventCount(),
                source.eventDetailsAvailable(),
                DiscrepancyType.PAYMENT_EVENT_COUNT_MISMATCH, DiscrepancyType.MISSING_PAYMENT_EVENTS, findings);
    }

    private void compareEventStreams(
            List<EventRef> localEvents,
            List<EventRef> sourceEvents,
            long sourceEventCount,
            boolean detailsAvailable,
            DiscrepancyType countMismatch,
            DiscrepancyType missingEvents,
            List<Discrepancy> findings) {
        long localCount = localEvents.size();
        if (localCount != sourceEventCount) {
            findings.add(Discrepancy.of(countMismatch, "eventCount",
                    String.valueOf(sourceEventCount), String.valueOf(localCount)));
        }
        if (!detailsAvailable) {
            return;
        }
        Set<String> known = localEvents.stream().map(EventRef::eventId).collect(Collectors.toSet());
        Set<String> missing = new LinkedHashSet<>();
        for (EventRef event : sourceEvents) {
            if (!known.contains(event.eventId())) {
                missing.add(event.eventId());
            }
        }
        if (!missing.isEmpty()) {
            findings.add(Discrepancy.of(missingEvents, "eventId", summarise(missing), null));
        }
    }

    private void compareHistoryCompleteness(
            LocalOrderView local, SourceOrderView source, List<Discrepancy> findings) {
        if (local.order().isEmpty()) {
            return;
        }
        OrderStatus status = source.order()
                .map(OrderSnapshot::status)
                .orElseGet(() -> local.order().get().status());

        Set<String> required = OrderLifecycle.requiredEventTypes(status);
        if (required.isEmpty()) {
            return;
        }
        Set<String> observed = local.orderEvents().stream()
                .map(EventRef::eventType)
                .collect(Collectors.toSet());
        for (String eventType : required) {
            if (!observed.contains(eventType)) {
                findings.add(Discrepancy.of(DiscrepancyType.INCOMPLETE_ORDER_HISTORY, eventType,
                        eventType + " required for status " + status.name(), "not received"));
            }
        }
    }

    private String summarise(Set<String> ids) {
        if (ids.size() <= MAX_LISTED_IDS) {
            return String.join(", ", ids);
        }
        String head = ids.stream().limit(MAX_LISTED_IDS).collect(Collectors.joining(", "));
        return head + ", ... (" + (ids.size() - MAX_LISTED_IDS) + " more)";
    }
}
