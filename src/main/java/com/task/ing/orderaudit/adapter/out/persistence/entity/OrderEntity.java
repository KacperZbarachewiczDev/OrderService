package com.task.ing.orderaudit.adapter.out.persistence.entity;

import com.task.ing.orderaudit.domain.model.OrderStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderEntity {

    @Id
    @Column(name = "order_id", nullable = false, length = 64)
    private String orderId;

    @Setter
    private String customerId;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private OrderStatus status;

    @Setter
    @Column(length = 3)
    private String currency;

    @Setter
    private BigDecimal totalAmount;

    @Setter
    private Instant sourceUpdatedAt;

    @Setter
    private String lastEventId;

    @Setter
    private String lastEventType;

    @Setter
    private Instant lastEventOccurredAt;

    @Setter
    private Long lastEventSequence;

    @Setter
    @Column(nullable = false)
    private Instant firstSeenAt;

    @Setter
    @Column(nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<OrderLineEntity> lines = new ArrayList<>();

    public OrderEntity(String orderId) {
        this.orderId = orderId;
    }

    public void syncLines(List<OrderLineEntity> desired) {
        Map<String, OrderLineEntity> byProduct = new LinkedHashMap<>();
        desired.forEach(line -> byProduct.put(line.getProductId(), line));

        lines.removeIf(existing -> !byProduct.containsKey(existing.getProductId()));
        lines.forEach(existing -> {
            OrderLineEntity target = byProduct.remove(existing.getProductId());
            existing.updateTo(target.getQuantity(), target.getUnitPrice(), target.getCurrency());
        });
        byProduct.values().forEach(line -> {
            line.setOrder(this);
            lines.add(line);
        });
    }
}
