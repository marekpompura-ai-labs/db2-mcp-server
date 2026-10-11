package io.github.marekpompura.db2mcp.policy.concurrency;

import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Component;

/**
 * A thin, fail-fast wrapper around {@link Semaphore} (ADR 0035): {@link
 * #tryAcquire()} is deliberately non-blocking, not a bounded wait - the
 * deliberate contrast is with Hikari's own connection pool, which queues a
 * caller up to {@code connection-timeout} instead of rejecting immediately
 * (see {@code HikariPoolConcurrencyIntegrationTest}, which demonstrates
 * that queuing behavior directly). This gate takes the opposite,
 * fail-fast design instead, matching this codebase's established policy
 * philosophy (ADR 0007/0018/0034: deterministic rejection over queuing).
 *
 * <p>Every caller MUST release exactly one permit for every successful
 * {@link #tryAcquire()}, in a {@code finally} block, regardless of how the
 * guarded work completes - a leaked permit here permanently shrinks this
 * server's effective concurrency bound for as long as the process runs.
 */
@Component
public class ToolExecutionAdmissionGate {

    private final Semaphore semaphore;

    public ToolExecutionAdmissionGate(ConcurrencyLimitsProperties properties) {
        this.semaphore = new Semaphore(properties.maxConcurrentToolExecutions());
    }

    public boolean tryAcquire() {
        return semaphore.tryAcquire();
    }

    public void release() {
        semaphore.release();
    }
}
