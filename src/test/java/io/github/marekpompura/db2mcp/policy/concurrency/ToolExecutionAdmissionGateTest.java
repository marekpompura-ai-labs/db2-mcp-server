package io.github.marekpompura.db2mcp.policy.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ToolExecutionAdmissionGateTest {

    @Test
    void tryAcquire_succeedsUpToTheBoundAndFailsBeyondIt() {
        ToolExecutionAdmissionGate gate = new ToolExecutionAdmissionGate(new ConcurrencyLimitsProperties(2));

        assertThat(gate.tryAcquire()).isTrue();
        assertThat(gate.tryAcquire()).isTrue();
        assertThat(gate.tryAcquire()).isFalse();
    }

    @Test
    void release_freesAPermitForASubsequentTryAcquire() {
        ToolExecutionAdmissionGate gate = new ToolExecutionAdmissionGate(new ConcurrencyLimitsProperties(1));
        assertThat(gate.tryAcquire()).isTrue();
        assertThat(gate.tryAcquire()).isFalse();

        gate.release();

        assertThat(gate.tryAcquire()).isTrue();
    }

    @Test
    void acquireReleaseCycle_doesNotDriftOverManyIterations() {
        // Proves no permit leak over time - a leak would manifest as a
        // later iteration spuriously failing to acquire, since the bound
        // would have silently drained toward zero.
        ToolExecutionAdmissionGate gate = new ToolExecutionAdmissionGate(new ConcurrencyLimitsProperties(1));

        for (int i = 0; i < 10_000; i++) {
            assertThat(gate.tryAcquire()).isTrue();
            gate.release();
        }
    }
}
