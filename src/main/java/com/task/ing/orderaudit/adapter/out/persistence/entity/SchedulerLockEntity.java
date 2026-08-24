package com.task.ing.orderaudit.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "scheduler_locks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SchedulerLockEntity {

    @Id
    @Column(length = 64)
    private String name;

    @Column(nullable = false)
    private Instant lockedUntil;

    @Column(nullable = false, length = 128)
    private String lockedBy;

    @Column(nullable = false)
    private Instant lockedAt;
}
