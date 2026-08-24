package com.task.ing.orderaudit.adapter.out.persistence.entity;

import com.task.ing.orderaudit.domain.audit.AuditRunStatus;
import com.task.ing.orderaudit.domain.audit.AuditTrigger;
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
@Table(name = "audit_runs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditRunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 16)
    private AuditTrigger triggerType;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AuditRunStatus status;

    private Instant windowFrom;

    @Column(nullable = false)
    private Instant windowTo;

    @Column(nullable = false)
    private Instant startedAt;

    @Setter
    private Instant finishedAt;

    @Setter
    @Column(nullable = false)
    private int ordersChecked;

    @Setter
    @Column(nullable = false)
    private int ordersWithIssues;

    @Setter
    @Column(nullable = false)
    private int ordersResolved;

    @Setter
    @Column(nullable = false)
    private int discrepanciesFound;

    @Setter
    @Column(nullable = false)
    private int ordersInconclusive;

    @Setter
    private String failureReason;

    public AuditRunEntity(
            AuditTrigger triggerType, Instant windowFrom, Instant windowTo, Instant startedAt) {
        this.triggerType = triggerType;
        this.status = AuditRunStatus.RUNNING;
        this.windowFrom = windowFrom;
        this.windowTo = windowTo;
        this.startedAt = startedAt;
    }
}
