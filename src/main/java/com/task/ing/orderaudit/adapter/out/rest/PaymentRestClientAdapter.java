package com.task.ing.orderaudit.adapter.out.rest;

import com.task.ing.orderaudit.adapter.out.rest.dto.EventCountResponse;
import com.task.ing.orderaudit.adapter.out.rest.dto.ModifiedOrdersResponse;
import com.task.ing.orderaudit.adapter.out.rest.dto.PaymentResponse;
import com.task.ing.orderaudit.application.port.out.PaymentSourceClient;
import com.task.ing.orderaudit.application.port.out.SourceEvent;
import com.task.ing.orderaudit.domain.model.Money;
import com.task.ing.orderaudit.domain.model.PaymentSnapshot;
import com.task.ing.orderaudit.domain.model.PaymentStatus;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class PaymentRestClientAdapter implements PaymentSourceClient {

    private static final int MAX_PAGES = 10_000;

    private final SourceHttpGateway gateway;
    private final SourceEventParser eventParser;

    @Override
    public Optional<PaymentSnapshot> fetchPayment(String orderId) {
        return gateway.getOptional("/payments/{orderId}", PaymentResponse.class, orderId)
                .map(this::toSnapshot);
    }

    @Override
    public List<SourceEvent> fetchEvents(String orderId) {
        String body = gateway.getRequired("/payments/{orderId}/events", String.class, orderId);
        return eventParser.parse(orderId, body);
    }

    @Override
    public long countEvents(String orderId) {
        return gateway.getOptional("/payments/{orderId}/events/count", EventCountResponse.class, orderId)
                .map(EventCountResponse::count)
                .orElse(0L);
    }

    @Override
    public List<String> findOrderIdsModifiedSince(Instant since) {
        Set<String> ids = new LinkedHashSet<>();
        String pageToken = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            ModifiedOrdersResponse response = pageToken == null
                    ? gateway.getRequired("/payments?modifiedSince={since}",
                    ModifiedOrdersResponse.class, since.toString())
                    : gateway.getRequired("/payments?modifiedSince={since}&pageToken={token}",
                    ModifiedOrdersResponse.class, since.toString(), pageToken);
            ids.addAll(response.orderIds());
            pageToken = response.nextPageToken();
            if (pageToken == null || pageToken.isBlank()) {
                break;
            }
        }
        return List.copyOf(ids);
    }

    private PaymentSnapshot toSnapshot(PaymentResponse response) {
        Money amount = response.amount() == null || response.currency() == null
                ? null
                : new Money(response.amount(), response.currency());
        return new PaymentSnapshot(
                response.paymentId(),
                response.orderId(),
                PaymentStatus.fromExternal(response.status()),
                amount,
                response.updatedAt());
    }
}
