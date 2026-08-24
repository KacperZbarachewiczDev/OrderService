package com.task.ing.orderaudit.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "notification_outbox")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationOutboxEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String recipients;

    @Column(nullable = false, length = 512)
    private String subject;

    @Column(nullable = false)
    private String body;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(nullable = false)
    private int attempts;

    @Column(nullable = false)
    private Instant nextAttemptAt;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant sentAt;

    private String lastError;

    private Long auditRunId;

    public NotificationOutboxEntity(
            String recipients, String subject, String body, Instant now, Long auditRunId) {
        this.recipients = recipients;
        this.subject = subject;
        this.body = body;
        this.status = "PENDING";
        this.attempts = 0;
        this.nextAttemptAt = now;
        this.createdAt = now;
        this.auditRunId = auditRunId;
    }
}
