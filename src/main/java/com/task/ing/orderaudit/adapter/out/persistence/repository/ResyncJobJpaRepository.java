package com.task.ing.orderaudit.adapter.out.persistence.repository;

import com.task.ing.orderaudit.adapter.out.persistence.entity.ResyncJobEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ResyncJobJpaRepository extends JpaRepository<ResyncJobEntity, Long> {

    @Modifying
    @Query(value = """
            insert into resync_jobs (order_id, status, requested_at, attempts)
            values (:orderId, 'PENDING', :now, 0)
            on conflict (order_id) where status in ('PENDING', 'RUNNING') do nothing
            """, nativeQuery = true)
    int insertIfNoneActive(@Param("orderId") String orderId, @Param("now") Instant now);

    @Query("""
            select j from ResyncJobEntity j
            where j.orderId = :orderId
              and j.status in (com.task.ing.orderaudit.domain.resync.ResyncStatus.PENDING,
                               com.task.ing.orderaudit.domain.resync.ResyncStatus.RUNNING)
            """)
    Optional<ResyncJobEntity> findActive(@Param("orderId") String orderId);

    @Modifying
    @Query("""
            update ResyncJobEntity j
            set j.status = com.task.ing.orderaudit.domain.resync.ResyncStatus.RUNNING,
                j.startedAt = :now,
                j.attempts = j.attempts + 1
            where j.id = :id
              and j.status = com.task.ing.orderaudit.domain.resync.ResyncStatus.PENDING
            """)
    int claim(@Param("id") Long id, @Param("now") Instant now);

    Optional<ResyncJobEntity> findFirstByOrderIdOrderByRequestedAtDescIdDesc(String orderId);

    @Query("""
            select j.id from ResyncJobEntity j
            where j.status = com.task.ing.orderaudit.domain.resync.ResyncStatus.PENDING
              and j.requestedAt < :before
            order by j.requestedAt
            """)
    List<Long> findStalePendingIds(@Param("before") Instant before, Pageable pageable);
}
