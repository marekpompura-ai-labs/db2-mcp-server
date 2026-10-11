package io.github.marekpompura.db2mcp.domain.error;

/**
 * Stable error categories exposed to the MCP client (master brief section
 * 11.4, ADR 0018) - distinct from {@link
 * io.github.marekpompura.db2mcp.domain.audit.PolicyDecisionReason}, which is
 * audit-log-internal only and never reaches a tool's thrown exception.
 *
 * <p>Not every category is currently reachable from this codebase's actual
 * policy set - {@link #RESULT_LIMIT_EXCEEDED} is not modeled as an error at
 * all - exceeding {@code maxRows} produces a successful, typed result with
 * {@code truncated: true}, not a rejection. Kept in the enum because the
 * brief names it as a stable category a future policy might need, not
 * because this codebase currently throws it - stated here rather than left
 * for a reader to wonder why it's never observed in tests. {@link
 * #POLICY_REJECTED} was in the same position until ADR 0035, which gave it
 * its first real caller: {@code AdmissionControlledToolCallback}'s
 * concurrent-tool-execution admission gate.
 */
public enum ErrorCategory {
    VALIDATION_FAILED,
    CAPABILITY_DISABLED,
    POLICY_REJECTED,
    SCOPE_DENIED,
    QUERY_TIMEOUT,
    RESULT_LIMIT_EXCEEDED,
    DB2_AUTHORIZATION_FAILED,
    DB2_OBJECT_NOT_FOUND,
    DB2_EXECUTION_FAILED,
    INTERNAL_ERROR
}
