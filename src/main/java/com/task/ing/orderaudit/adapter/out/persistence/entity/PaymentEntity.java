package com.task.ing.orderaudit.adapter.out.persistence.entity;

import com.task.ing.orderaudit.domain.model.PaymentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentEntity {

    @Id
    @Column(name = "payment_id", nullable = false, length = 64)
    private String paymentId;

    @Column(nullable = false, length = 64)
    private String orderId;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PaymentStatus status;

    @Setter
    private BigDecimal amount;

    @Setter
    @Column(length = 3)
    private String currency;

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

    public PaymentEntity(String paymentId, String orderId) {
        this.paymentId = paymentId;
        this.orderId = orderId;
    }
}
