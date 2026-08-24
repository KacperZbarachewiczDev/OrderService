package com.task.ing.orderaudit.config;

import com.task.ing.orderaudit.application.config.AuditProperties;
import com.task.ing.orderaudit.domain.audit.AuditEngine;
import com.task.ing.orderaudit.domain.notification.AuditNotificationComposer;
import com.task.ing.orderaudit.domain.outbox.OutboxRetryPolicy;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DomainConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public AuditEngine auditEngine() {
        return new AuditEngine();
    }

    @Bean
    public AuditNotificationComposer auditNotificationComposer(AuditProperties properties) {
        return new AuditNotificationComposer(
                properties.notification().maxListedIssues(), properties.notification().issuesUrl());
    }

    @Bean
    public OutboxRetryPolicy outboxRetryPolicy(AuditProperties properties) {
        return new OutboxRetryPolicy(
                properties.outbox().maxAttempts(),
                properties.outbox().initialBackoff(),
                properties.outbox().maxBackoff());
    }
}
