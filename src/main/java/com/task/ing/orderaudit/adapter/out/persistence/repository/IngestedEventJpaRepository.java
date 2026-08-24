package com.task.ing.orderaudit.adapter.out.persistence.repository;

import com.task.ing.orderaudit.adapter.out.persistence.entity.IngestedEventEntity;
import com.task.ing.orderaudit.domain.model.EventRef;
import com.task.ing.orderaudit.domain.model.EventSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface IngestedEventJpaRepository extends JpaRepository<IngestedEventEntity, Long> {

    @Modifying
    @Query(value = """
            insert into ingested_events
                (event_id, source, order_id, aggregate_id, event_type,
                 occurred_at, sequence_no, payload, received_at, origin)
            values (:eventId, :source, :orderId, :aggregateId, :eventType,
                    :occurredAt, :sequenceNo, cast(:payload as jsonb), :receivedAt, :origin)
            on conflict (source, event_id) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("eventId") String eventId,
            @Param("source") String source,
            @Param("orderId") String orderId,
            @Param("aggregateId") String aggregateId,
            @Param("eventType") String eventType,
            @Param("occurredAt") Instant occurredAt,
            @Param("sequenceNo") Long sequenceNo,
            @Param("payload") String payload,
            @Param("receivedAt") Instant receivedAt,
            @Param("origin") String origin);

    @Query("""
            select new com.task.ing.orderaudit.domain.model.EventRef(
                e.eventId, e.eventType, e.occurredAt, e.sequenceNo)
            from IngestedEventEntity e
            where e.orderId = :orderId and e.source = :source
            order by e.occurredAt, e.eventId
            """)
    List<EventRef> findRefs(@Param("orderId") String orderId, @Param("source") EventSource source);
}
