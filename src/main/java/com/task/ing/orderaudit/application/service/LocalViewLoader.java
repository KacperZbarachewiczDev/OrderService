package com.task.ing.orderaudit.application.service;

import com.task.ing.orderaudit.application.port.out.EventStorePort;
import com.task.ing.orderaudit.application.port.out.OrderProjectionPort;
import com.task.ing.orderaudit.application.port.out.PaymentProjectionPort;
import com.task.ing.orderaudit.domain.audit.LocalOrderView;
import com.task.ing.orderaudit.domain.model.EventSource;
import com.task.ing.orderaudit.domain.model.OrderProjection;
import com.task.ing.orderaudit.domain.model.PaymentProjection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LocalViewLoader {

    private final OrderProjectionPort orderProjections;
    private final PaymentProjectionPort paymentProjections;
    private final EventStorePort eventStore;

    public LocalOrderView load(String orderId) {
        return new LocalOrderView(
                orderId,
                orderProjections.find(orderId).map(OrderProjection::snapshot),
                paymentProjections.findByOrderId(orderId).map(PaymentProjection::snapshot),
                eventStore.findEventRefs(orderId, EventSource.ORDER),
                eventStore.findEventRefs(orderId, EventSource.PAYMENT));
    }
}
