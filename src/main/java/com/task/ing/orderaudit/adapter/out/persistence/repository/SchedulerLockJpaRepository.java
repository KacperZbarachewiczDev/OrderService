package com.task.ing.orderaudit.adapter.out.persistence.repository;

import com.task.ing.orderaudit.adapter.out.persistence.entity.SchedulerLockEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface SchedulerLockJpaRepository extends JpaRepository<SchedulerLockEntity, String> {

    @Modifying
    @Query(value = """
            insert into scheduler_locks (name, locked_until, locked_by, locked_at)
            values (:name, :lockedUntil, :owner, :now)
            on conflict (name) do update
                set locked_until = :lockedUntil, locked_by = :owner, locked_at = :now
                where scheduler_locks.locked_until <= :now
            """, nativeQuery = true)
    int acquire(
            @Param("name") String name,
            @Param("owner") String owner,
            @Param("now") Instant now,
            @Param("lockedUntil") Instant lockedUntil);

    @Modifying
    @Query(value = """
            update scheduler_locks set locked_until = :now
            where name = :name and locked_by = :owner
            """, nativeQuery = true)
    int release(@Param("name") String name, @Param("owner") String owner, @Param("now") Instant now);
}
