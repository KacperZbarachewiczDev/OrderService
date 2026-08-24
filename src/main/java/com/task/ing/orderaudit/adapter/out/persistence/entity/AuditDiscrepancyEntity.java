package com.task.ing.orderaudit.adapter.out.persistence.entity;

import com.task.ing.orderaudit.domain.audit.DiscrepancyType;
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

import java.time.Instant;

@Entity
@Table(name = "audit_discrepancies")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditDiscrepancyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long issueId;

    private Long auditRunId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 48)
    private DiscrepancyType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Severity severity;

    @Column(name = "field", length = 128)
    private String field;

    private String expectedValue;

    private String actualValue;

    @Column(nullable = false)
    private Instant detectedAt;

    public AuditDiscrepancyEntity(
            Long issueId,
            Long auditRunId,
            DiscrepancyType type,
            Severity severity,
            String field,
            String expectedValue,
            String actualValue,
            Instant detectedAt) {
        this.issueId = issueId;
        this.auditRunId = auditRunId;
        this.type = type;
        this.severity = severity;
        this.field = field;
        this.expectedValue = expectedValue;
        this.actualValue = actualValue;
        this.detectedAt = detectedAt;
    }
}
