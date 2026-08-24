package com.task.ing.orderaudit.application.port.out;

import com.task.ing.orderaudit.domain.model.OrderProjection;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OrderProjectionPort {

    Optional<OrderProjection> find(String orderId);

    Optional<OrderProjection> findForUpdate(String orderId, Instant now);

    void save(OrderProjection projection, Instant now);

    void overwrite(OrderProjection projection, Instant now);

    List<String> findOrderIdsUpdatedBetween(Instant from, Instant to, int limit, String afterOrderId);
}
