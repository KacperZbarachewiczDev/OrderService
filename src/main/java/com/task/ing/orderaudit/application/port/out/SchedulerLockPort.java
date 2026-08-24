package com.task.ing.orderaudit.application.port.out;

import java.time.Duration;
import java.time.Instant;

public interface SchedulerLockPort {

    boolean acquire(String name, String owner, Instant now, Duration leaseFor);

    void release(String name, String owner, Instant now);
}
