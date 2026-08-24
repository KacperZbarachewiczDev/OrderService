package com.task.ing.orderaudit.application.port.out;

import com.task.ing.orderaudit.domain.model.PaymentProjection;

import java.time.Instant;
import java.util.Optional;

public interface PaymentProjectionPort {

    Optional<PaymentProjection> findByOrderId(String orderId);

    Optional<PaymentProjection> findForUpdate(String paymentId, String orderId, Instant now);

    void save(PaymentProjection projection, Instant now);

    void overwrite(PaymentProjection projection, Instant now);
}
