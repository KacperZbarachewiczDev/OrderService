package com.task.ing.orderaudit.adapter.out.persistence.repository;

import com.task.ing.orderaudit.adapter.out.persistence.entity.AuditDiscrepancyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditDiscrepancyJpaRepository extends JpaRepository<AuditDiscrepancyEntity, Long> {

    List<AuditDiscrepancyEntity> findByIssueIdOrderByIdAsc(Long issueId);

    void deleteByIssueId(Long issueId);
}
