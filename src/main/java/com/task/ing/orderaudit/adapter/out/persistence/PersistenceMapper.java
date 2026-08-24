package com.task.ing.orderaudit.adapter.out.persistence;

import com.task.ing.orderaudit.adapter.out.persistence.entity.AuditDiscrepancyEntity;
import com.task.ing.orderaudit.adapter.out.persistence.entity.AuditIssueEntity;
import com.task.ing.orderaudit.adapter.out.persistence.entity.AuditRunEntity;
import com.task.ing.orderaudit.adapter.out.persistence.entity.OrderEntity;
import com.task.ing.orderaudit.adapter.out.persistence.entity.OrderLineEntity;
import com.task.ing.orderaudit.adapter.out.persistence.entity.PaymentEntity;
import com.task.ing.orderaudit.adapter.out.persistence.entity.ResyncJobEntity;
import com.task.ing.orderaudit.domain.audit.AuditIssue;
import com.task.ing.orderaudit.domain.audit.AuditRun;
import com.task.ing.orderaudit.domain.audit.AuditRunStats;
import com.task.ing.orderaudit.domain.audit.Discrepancy;
import com.task.ing.orderaudit.domain.model.EventRef;
import com.task.ing.orderaudit.domain.model.Money;
import com.task.ing.orderaudit.domain.model.OrderLine;
import com.task.ing.orderaudit.domain.model.OrderProjection;
import com.task.ing.orderaudit.domain.model.OrderSnapshot;
import com.task.ing.orderaudit.domain.model.PaymentProjection;
import com.task.ing.orderaudit.domain.model.PaymentSnapshot;
import com.task.ing.orderaudit.domain.resync.ResyncJob;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

public final class PersistenceMapper {

    private PersistenceMapper() {
    }

    public static Money money(BigDecimal amount, String currency) {
        if (amount == null || currency == null) {
            return null;
        }
        return new Money(amount, currency);
    }

    public static OrderSnapshot toSnapshot(OrderEntity entity) {
        List<OrderLine> lines = entity.getLines().stream()
                .sorted(Comparator.comparing(OrderLineEntity::getProductId))
                .map(line -> new OrderLine(
                        line.getProductId(), line.getQuantity(), new Money(line.getUnitPrice(), line.getCurrency())))
                .toList();
        return new OrderSnapshot(
                entity.getOrderId(),
                entity.getCustomerId(),
                entity.getStatus(),
                money(entity.getTotalAmount(), entity.getCurrency()),
                lines,
                entity.getSourceUpdatedAt());
    }

    public static OrderProjection toProjection(OrderEntity entity) {
        return new OrderProjection(toSnapshot(entity), lastEventRef(
                entity.getLastEventId(), entity.getLastEventType(),
                entity.getLastEventOccurredAt(), entity.getLastEventSequence()));
    }

    public static PaymentSnapshot toSnapshot(PaymentEntity entity) {
        return new PaymentSnapshot(
                entity.getPaymentId(),
                entity.getOrderId(),
                entity.getStatus(),
                money(entity.getAmount(), entity.getCurrency()),
                entity.getLastEventOccurredAt());
    }

    public static PaymentProjection toProjection(PaymentEntity entity) {
        return new PaymentProjection(toSnapshot(entity), lastEventRef(
                entity.getLastEventId(), entity.getLastEventType(),
                entity.getLastEventOccurredAt(), entity.getLastEventSequence()));
    }

    public static AuditRun toDomain(AuditRunEntity entity) {
        return new AuditRun(
                entity.getId(),
                entity.getTriggerType(),
                entity.getStatus(),
                entity.getWindowFrom(),
                entity.getWindowTo(),
                entity.getStartedAt(),
                entity.getFinishedAt(),
                new AuditRunStats(
                        entity.getOrdersChecked(),
                        entity.getOrdersWithIssues(),
                        entity.getOrdersResolved(),
                        entity.getDiscrepanciesFound(),
                        entity.getOrdersInconclusive()),
                entity.getFailureReason());
    }

    public static AuditIssue toDomain(AuditIssueEntity entity) {
        return new AuditIssue(
                entity.getId(),
                entity.getOrderId(),
                entity.getStatus(),
                entity.getHighestSeverity(),
                entity.getDiscrepancyCount(),
                entity.getFirstDetectedAt(),
                entity.getLastDetectedAt(),
                entity.getResolvedAt(),
                entity.getLastAuditRunId());
    }

    public static Discrepancy toDomain(AuditDiscrepancyEntity entity) {
        return new Discrepancy(
                entity.getType(), entity.getField(), entity.getExpectedValue(), entity.getActualValue());
    }

    public static ResyncJob toDomain(ResyncJobEntity entity) {
        return new ResyncJob(
                entity.getId(),
                entity.getOrderId(),
                entity.getStatus(),
                entity.getRequestedAt(),
                entity.getStartedAt(),
                entity.getFinishedAt(),
                entity.getAttempts(),
                entity.getRemainingDiscrepancies(),
                entity.getFailureReason());
    }

    private static EventRef lastEventRef(String eventId, String eventType, java.time.Instant at, Long sequence) {
        if (eventId == null || eventType == null || at == null) {
            return null;
        }
        return new EventRef(eventId, eventType, at, sequence);
    }
}
