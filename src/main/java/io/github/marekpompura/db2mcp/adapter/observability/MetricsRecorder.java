package io.github.marekpompura.db2mcp.adapter.observability;

import io.github.marekpompura.db2mcp.domain.audit.PolicyDecisionReason;
import io.github.marekpompura.db2mcp.policy.capability.CapabilityProfile;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Records the metric set from master brief section 9.2 for executeQuery
 * (ADR 0011). Every tag value comes from a closed enum, the tool name, or a
 * SQLSTATE code - never a correlation ID, raw SQL, or free text, so
 * cardinality stays bounded by construction.
 */
@Component
public class MetricsRecorder {

    private final MeterRegistry registry;

    public MetricsRecorder(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordCall(String tool, String outcome, long durationMillis) {
        Counter.builder("mcp.tool.calls")
                .tag("tool", tool)
                .tag("outcome", outcome)
                .register(registry)
                .increment();
        Timer.builder("mcp.tool.duration").tag("tool", tool).register(registry).record(durationMillis, TimeUnit.MILLISECONDS);
    }

    public void recordRejected(String tool, PolicyDecisionReason reason) {
        Counter.builder("mcp.tool.rejected")
                .tag("tool", tool)
                .tag("reason", reason.name())
                .register(registry)
                .increment();
    }

    public void recordRows(String tool, int rowCount) {
        DistributionSummary.builder("mcp.result.rows").tag("tool", tool).register(registry).record(rowCount);
    }

    /**
     * P1-3 (2026-09-01 master-brief gap review, #40, ADR 0041): the missing
     * result-size meter the review found - {@link #recordRows} counts rows,
     * never bytes. {@code bytes} is each caller's own already-computed
     * response payload size (the same running total already enforced
     * against {@code maxPayloadBytes}), not recomputed here.
     */
    public void recordResultBytes(String tool, long bytes) {
        DistributionSummary.builder("mcp.result.bytes").tag("tool", tool).register(registry).record(bytes);
    }

    public void recordDb2QueryDuration(String tool, long durationMillis) {
        Timer.builder("db2.query.duration")
                .tag("tool", tool)
                .register(registry)
                .record(durationMillis, TimeUnit.MILLISECONDS);
    }

    public void recordDb2Error(String sqlState) {
        Counter.builder("db2.errors")
                .tag("sqlstate", sqlState != null ? sqlState : "unknown")
                .register(registry)
                .increment();
    }

    public void recordPolicyDecision(CapabilityProfile profile, PolicyDecisionReason decision) {
        Counter.builder("security.policy.decisions")
                .tag("profile", profile.name())
                .tag("decision", decision.name())
                .register(registry)
                .increment();
    }
}
