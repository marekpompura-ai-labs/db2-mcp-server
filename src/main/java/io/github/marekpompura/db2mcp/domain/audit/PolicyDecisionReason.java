package io.github.marekpompura.db2mcp.domain.audit;

/**
 * Audit-internal reason codes (ADR 0010). Not exposed through any tool's
 * return value or thrown exception - purely what gets written to the audit
 * log. Deliberately not the same thing as the public structured error model
 * (master brief section 11.4) a client actually sees - see {@link
 * io.github.marekpompura.db2mcp.domain.error.ErrorCategory} and {@link
 * io.github.marekpompura.db2mcp.domain.error.McpToolException} (ADR 0018)
 * for that.
 */
public enum PolicyDecisionReason {
    ALLOWED,
    CAPABILITY_DISABLED,
    INVALID_STATEMENT,
    SCHEMA_NOT_ALLOWED,
    COMPLEXITY_EXCEEDED,
    CONCURRENCY_LIMIT_EXCEEDED,
    EXECUTION_FAILED,
    // Issue #46 (2026-09-01 master-brief gap review, P2-2): distinct from
    // EXECUTION_FAILED so an operator can tell "this server proactively
    // cancelled an in-flight query because its MCP client disconnected"
    // apart from a genuine DB2 error or a query that ran past its own
    // configured statement timeout on its own - both of the latter two
    // still classify as EXECUTION_FAILED. See ActiveStatementRegistry.
    CANCELLED,
    // Issue #62 (2026-09-09 ChatGPT re-review, RR-P0-1): the response could
    // not be brought within maxPayloadBytes at all - not a truncated
    // success (which audits as ALLOWED with truncated=true), but a refusal,
    // because even the empty envelope was over budget. See PayloadBudget.
    PAYLOAD_LIMIT_EXCEEDED
}
