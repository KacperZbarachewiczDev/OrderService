package com.task.ing.orderaudit.application.port.out;

import com.task.ing.orderaudit.domain.model.PaymentSnapshot;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PaymentSourceClient {

    Optional<PaymentSnapshot> fetchPayment(String orderId);

    List<SourceEvent> fetchEvents(String orderId);

    long countEvents(String orderId);

    List<String> findOrderIdsModifiedSince(Instant since);
}
