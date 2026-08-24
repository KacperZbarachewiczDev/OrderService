package com.task.ing.orderaudit.integration;

import com.task.ing.orderaudit.application.port.in.RunAuditUseCase;
import com.task.ing.orderaudit.application.port.out.SchedulerLockPort;
import com.task.ing.orderaudit.domain.audit.AuditTrigger;
import com.task.ing.orderaudit.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulerLockIT extends AbstractIntegrationTest {

    private static final String LOCK = "test-lock";

    @Autowired
    private SchedulerLockPort schedulerLock;

    @Autowired
    private RunAuditUseCase runAudit;

    @Test
    @DisplayName("only the first caller gets the lock")
    void grants_the_lock_once() {
        Instant now = Instant.now();

        assertThat(schedulerLock.acquire(LOCK, "node-a", now, Duration.ofMinutes(10))).isTrue();
        assertThat(schedulerLock.acquire(LOCK, "node-b", now, Duration.ofMinutes(10))).isFalse();
    }

    @Test
    @DisplayName("releasing hands the lock straight to the next caller")
    void releases_the_lock() {
        Instant now = Instant.now();
        schedulerLock.acquire(LOCK, "node-a", now, Duration.ofMinutes(10));

        schedulerLock.release(LOCK, "node-a", now);

        assertThat(schedulerLock.acquire(LOCK, "node-b", now, Duration.ofMinutes(10))).isTrue();
    }

    @Test
    @DisplayName("an expired lease can be taken over")
    void lets_another_node_take_over_an_abandoned_lock() {
        Instant now = Instant.now();
        schedulerLock.acquire(LOCK, "crashed-node", now, Duration.ofSeconds(1));

        assertThat(schedulerLock.acquire(LOCK, "node-b", now.plusSeconds(2), Duration.ofMinutes(10)))
                .isTrue();
    }

    @Test
    @DisplayName("a node that does not hold the lock cannot release it")
    void ignores_a_release_from_someone_else() {
        Instant now = Instant.now();
        schedulerLock.acquire(LOCK, "node-a", now, Duration.ofMinutes(10));

        schedulerLock.release(LOCK, "node-b", now);

        assertThat(schedulerLock.acquire(LOCK, "node-c", now, Duration.ofMinutes(10))).isFalse();
    }

    @Test
    @DisplayName("eight nodes racing for the lock produce exactly one winner")
    void survives_a_stampede() throws Exception {
        Instant now = Instant.now();
        try (ExecutorService nodes = Executors.newFixedThreadPool(8)) {
            List<Callable<Boolean>> attempts = IntStream.range(0, 8)
                    .mapToObj(i -> (Callable<Boolean>) () ->
                            schedulerLock.acquire(LOCK, "node-" + i, now, Duration.ofMinutes(10)))
                    .toList();

            List<Future<Boolean>> results = nodes.invokeAll(attempts);

            long winners = results.stream().filter(this::valueOf).count();
            assertThat(winners).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a second audit cannot start while the first still holds the lock")
    void refuses_a_concurrent_audit_run() {
        orderService.modifiedSince();
        paymentService.modifiedSince();
        schedulerLock.acquire("daily-audit", "someone-else", Instant.now(), Duration.ofMinutes(10));

        assertThat(runAudit.run(AuditTrigger.SCHEDULED)).isEmpty();
        assertThat(count("audit_runs")).isZero();
    }

    private boolean valueOf(Future<Boolean> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException(e);
        }
    }
}
