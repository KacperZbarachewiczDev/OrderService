package com.task.ing.orderaudit.adapter.out.persistence.entity;

import com.task.ing.orderaudit.domain.audit.IssueStatus;
import com.task.ing.orderaudit.domain.audit.Severity;
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
@Table(name = "audit_issues")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditIssueEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String orderId;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private IssueStatus status;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Severity highestSeverity;

    @Setter
    @Column(nullable = false)
    private int discrepancyCount;

    @Column(nullable = false)
    private Instant firstDetectedAt;

    @Setter
    @Column(nullable = false)
    private Instant lastDetectedAt;

    @Setter
    private Instant resolvedAt;

    @Setter
    private Long lastAuditRunId;

    public AuditIssueEntity(String orderId, Instant detectedAt) {
        this.orderId = orderId;
        this.firstDetectedAt = detectedAt;
        this.lastDetectedAt = detectedAt;
    }
}
