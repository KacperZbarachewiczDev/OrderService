package com.task.ing.orderaudit.adapter.out.persistence;

import com.task.ing.orderaudit.adapter.out.persistence.repository.SchedulerLockJpaRepository;
import com.task.ing.orderaudit.application.port.out.SchedulerLockPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Repository
@RequiredArgsConstructor
public class SchedulerLockJpaAdapter implements SchedulerLockPort {

    private final SchedulerLockJpaRepository repository;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean acquire(String name, String owner, Instant now, Duration leaseFor) {
        return repository.acquire(name, owner, now, now.plus(leaseFor)) == 1;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(String name, String owner, Instant now) {
        repository.release(name, owner, now);
    }
}
