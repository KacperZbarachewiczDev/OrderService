package com.task.ing.orderaudit.adapter.out.persistence.repository;

import com.task.ing.orderaudit.adapter.out.persistence.entity.AuditIssueEntity;
import com.task.ing.orderaudit.domain.audit.IssueStatus;
import com.task.ing.orderaudit.domain.audit.Severity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AuditIssueJpaRepository extends JpaRepository<AuditIssueEntity, Long> {

    Optional<AuditIssueEntity> findByOrderId(String orderId);

    @Query("""
            select i from AuditIssueEntity i
            where i.status in :statuses and i.highestSeverity in :severities
            order by i.lastDetectedAt desc, i.id desc
            """)
    Page<AuditIssueEntity> search(
            @Param("statuses") Collection<IssueStatus> statuses,
            @Param("severities") Collection<Severity> severities,
            Pageable pageable);

    @Query("""
            select i.orderId from AuditIssueEntity i
            where i.status = com.task.ing.orderaudit.domain.audit.IssueStatus.OPEN
              and (:after is null or i.orderId > :after)
            order by i.orderId
            """)
    List<String> findOpenOrderIds(@Param("after") String after, Pageable pageable);

    long countByStatus(IssueStatus status);
}
