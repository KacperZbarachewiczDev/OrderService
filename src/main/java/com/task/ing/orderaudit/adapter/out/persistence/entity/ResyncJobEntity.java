package com.task.ing.orderaudit.adapter.out.persistence.entity;

import com.task.ing.orderaudit.domain.resync.ResyncStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "resync_jobs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ResyncJobEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String orderId;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ResyncStatus status;

    @Column(nullable = false)
    private Instant requestedAt;

    @Setter
    private Instant startedAt;

    @Setter
    private Instant finishedAt;

    @Setter
    @Column(nullable = false)
    private int attempts;

    @Setter
    private Integer remainingDiscrepancies;

    @Setter
    private String failureReason;

    public ResyncJobEntity(String orderId, Instant requestedAt) {
        this.orderId = orderId;
        this.requestedAt = requestedAt;
        this.status = ResyncStatus.PENDING;
    }
}
