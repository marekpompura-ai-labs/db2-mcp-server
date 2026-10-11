package io.github.marekpompura.db2mcp.domain.audit;

/**
 * One reconstructable record of a tool call's outcome (ADR 0010, master
 * brief section 8). Never carries raw SQL, row data, or credentials -
 * sqlFingerprint is a normalized "STATEMENT_TYPE[schema.table,...]" form,
 * never literal values or column names.
 */
public record AuditEvent(
        String correlationId,
        String toolName,
        PolicyDecisionReason decision,
        String sqlFingerprint,
        long durationMillis,
        Integer rowCount,
        Boolean truncated,
        String sqlState) {

    public static AuditEvent rejected(
            String correlationId, String toolName, PolicyDecisionReason reason, String sqlFingerprint, long durationMillis) {
        return new AuditEvent(correlationId, toolName, reason, sqlFingerprint, durationMillis, null, null, null);
    }

    /**
     * {@code rowCount}/{@code truncated} are boxed, matching the record's
     * own field types, not narrowed to {@code int}/{@code boolean} - a
     * single-object result (e.g. {@code describeProcedure},
     * {@code getProcedureSource}) has no row count to report and legitimately
     * passes {@code null} here. A prior primitive-typed version of this
     * factory compiled fine but threw a {@code NullPointerException} on
     * unboxing for exactly that case - a real bug, found while extending
     * this same instrumentation pattern for {@code getCapabilities} (ADR
     * 0028), not shipped undiscovered.
     */
    public static AuditEvent allowed(
            String correlationId,
            String toolName,
            String sqlFingerprint,
            long durationMillis,
            Integer rowCount,
            Boolean truncated) {
        return new AuditEvent(
                correlationId,
                toolName,
                PolicyDecisionReason.ALLOWED,
                sqlFingerprint,
                durationMillis,
                rowCount,
                truncated,
                null);
    }

    public static AuditEvent failed(
            String correlationId, String toolName, String sqlFingerprint, long durationMillis, String sqlState) {
        return new AuditEvent(
                correlationId,
                toolName,
                PolicyDecisionReason.EXECUTION_FAILED,
                sqlFingerprint,
                durationMillis,
                null,
                null,
                sqlState);
    }

    /**
     * Issue #46: a query this server itself cancelled (its MCP client
     * disconnected before the query finished), not a DB2 error or a
     * self-inflicted statement timeout - both of which stay {@link #failed}.
     */
    public static AuditEvent cancelled(
            String correlationId, String toolName, String sqlFingerprint, long durationMillis, String sqlState) {
        return new AuditEvent(
                correlationId,
                toolName,
                PolicyDecisionReason.CANCELLED,
                sqlFingerprint,
                durationMillis,
                null,
                null,
                sqlState);
    }
}
