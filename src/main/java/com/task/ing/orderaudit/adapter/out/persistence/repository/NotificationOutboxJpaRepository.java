package com.task.ing.orderaudit.adapter.out.persistence.repository;

import com.task.ing.orderaudit.adapter.out.persistence.entity.NotificationOutboxEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface NotificationOutboxJpaRepository extends JpaRepository<NotificationOutboxEntity, Long> {

    @Query(value = """
            select id from notification_outbox
            where status = 'PENDING' and next_attempt_at <= :now
            order by next_attempt_at
            limit :maxRows
            for update skip locked
            """, nativeQuery = true)
    List<Long> lockDueIds(@Param("now") Instant now, @Param("maxRows") int maxRows);

    @Modifying
    @Query(value = """
            update notification_outbox
            set attempts = attempts + 1, next_attempt_at = :leaseUntil
            where id in (:ids)
            """, nativeQuery = true)
    int markClaimed(@Param("ids") List<Long> ids, @Param("leaseUntil") Instant leaseUntil);

    @Modifying
    @Query(value = """
            update notification_outbox
            set status = 'SENT', sent_at = :now, last_error = null
            where id = :id
            """, nativeQuery = true)
    int markSent(@Param("id") Long id, @Param("now") Instant now);

    @Modifying
    @Query(value = """
            update notification_outbox
            set next_attempt_at = :nextAttemptAt, last_error = :error
            where id = :id
            """, nativeQuery = true)
    int reschedule(
            @Param("id") Long id,
            @Param("error") String error,
            @Param("nextAttemptAt") Instant nextAttemptAt);

    @Modifying
    @Query(value = """
            update notification_outbox
            set status = 'FAILED', last_error = :error, next_attempt_at = :now
            where id = :id
            """, nativeQuery = true)
    int markFailed(@Param("id") Long id, @Param("error") String error, @Param("now") Instant now);

    List<NotificationOutboxEntity> findByStatusOrderByIdAsc(String status);
}
