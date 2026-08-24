package com.task.ing.orderaudit.adapter.out.persistence.repository;

import com.task.ing.orderaudit.adapter.out.persistence.entity.AuditRunEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

public interface AuditRunJpaRepository extends JpaRepository<AuditRunEntity, Long> {

    @Query("""
            select r.windowTo from AuditRunEntity r
            where r.status = com.task.ing.orderaudit.domain.audit.AuditRunStatus.COMPLETED
            order by r.windowTo desc
            """)
    List<Instant> findLatestCompletedWindowEnd(Pageable pageable);

    Page<AuditRunEntity> findAllByOrderByStartedAtDesc(Pageable pageable);
}
