package io.github.marekpompura.db2mcp.configuration;

import io.github.marekpompura.db2mcp.adapter.observability.AuditLogger;
import io.github.marekpompura.db2mcp.adapter.observability.MetricsRecorder;
import io.github.marekpompura.db2mcp.adapter.observability.SpanRecorder;
import io.github.marekpompura.db2mcp.domain.audit.AuditEvent;
import io.github.marekpompura.db2mcp.domain.audit.PolicyDecisionReason;
import io.github.marekpompura.db2mcp.domain.error.ErrorCategory;
import io.github.marekpompura.db2mcp.domain.error.McpToolException;
import io.github.marekpompura.db2mcp.policy.capability.CapabilityProfile;
import io.github.marekpompura.db2mcp.policy.concurrency.ToolExecutionAdmissionGate;
import io.micrometer.tracing.Span;
import java.util.UUID;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * Decorates every registered {@link ToolCallback} with the concurrent-
 * tool-execution admission gate master brief section 7.5 requires ("bound
 * concurrent tool executions") but nothing previously implemented (ADR
 * 0035) - {@code InFlightRequestTracker} (ADR 0031) only does
 * shutdown-drain bookkeeping, it never throttles.
 *
 * <p>Wired around every tool uniformly in {@code McpConfig}, not just
 * {@code executeQuery} - a single shared choke point rather than
 * duplicating a semaphore-acquire call into every adapter method. {@code
 * Db2QueryAdapter} and every other adapter are unchanged: this is a
 * strictly additive outer gate, and their own capability/structural/
 * schema/complexity gates still run exactly as before, for calls that get
 * past this one.
 *
 * <p>Only {@link #call(String)} is overridden - the default {@code
 * call(String, ToolContext)} on {@link ToolCallback} already delegates to
 * {@code this.call(input)} (confirmed by decompiling this project's pinned
 * Spring AI dependency), so it is automatically routed through the gate
 * too without a second override.
 */
final class AdmissionControlledToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final ToolExecutionAdmissionGate gate;
    private final CapabilityProfile capabilityProfile;
    private final AuditLogger auditLogger;
    private final MetricsRecorder metricsRecorder;
    private final SpanRecorder spanRecorder;

    AdmissionControlledToolCallback(
            ToolCallback delegate,
            ToolExecutionAdmissionGate gate,
            CapabilityProfile capabilityProfile,
            AuditLogger auditLogger,
            MetricsRecorder metricsRecorder,
            SpanRecorder spanRecorder) {
        this.delegate = delegate;
        this.gate = gate;
        this.capabilityProfile = capabilityProfile;
        this.auditLogger = auditLogger;
        this.metricsRecorder = metricsRecorder;
        this.spanRecorder = spanRecorder;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        String toolName = delegate.getToolDefinition().name();
        if (!gate.tryAcquire()) {
            String correlationId = UUID.randomUUID().toString();
            recordRejection(toolName, correlationId);
            throw new McpToolException(
                    ErrorCategory.POLICY_REJECTED,
                    "Too many concurrent tool executions in flight",
                    null,
                    correlationId);
        }
        try {
            return delegate.call(toolInput);
        } finally {
            // Unconditional, in finally: this MUST run whether the delegate
            // succeeds, throws its own McpToolException, or throws anything
            // else - a single permit leak here permanently shrinks this
            // server's effective concurrency bound for the rest of the
            // process's life.
            gate.release();
        }
    }

    private void recordRejection(String toolName, String correlationId) {
        auditLogger.record(AuditEvent.rejected(
                correlationId, toolName, PolicyDecisionReason.CONCURRENCY_LIMIT_EXCEEDED, null, 0));
        metricsRecorder.recordCall(toolName, "rejected", 0);
        metricsRecorder.recordPolicyDecision(capabilityProfile, PolicyDecisionReason.CONCURRENCY_LIMIT_EXCEEDED);
        metricsRecorder.recordRejected(toolName, PolicyDecisionReason.CONCURRENCY_LIMIT_EXCEEDED);
        Span span = spanRecorder.startRoot("mcp.tool " + toolName);
        span.tag("tool", toolName);
        span.tag("correlationId", correlationId);
        span.tag("outcome", "rejected");
        span.tag("reason", PolicyDecisionReason.CONCURRENCY_LIMIT_EXCEEDED.name());
        span.end();
    }
}
