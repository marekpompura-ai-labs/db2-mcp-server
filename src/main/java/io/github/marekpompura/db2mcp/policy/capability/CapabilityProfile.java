package io.github.marekpompura.db2mcp.policy.capability;

/**
 * Which tool capabilities are enabled. See docs/master-brief.md section 6.
 * metadata-only is the safe public default; safe-query and advanced-query
 * must be deliberately opted into.
 */
public enum CapabilityProfile {
    METADATA_ONLY,
    SAFE_QUERY,
    ADVANCED_QUERY;

    public boolean allowsQueryExecution() {
        return this != METADATA_ONLY;
    }

    /**
     * Shared enforcement gate for row-data tools (ADR 0006/ADR 0032) -
     * {@code executeQuery} and {@code lookupRefData} both call this with
     * their own tool name instead of each keeping a private, byte-for-byte
     * duplicated copy of the same check.
     */
    public void requireQueryExecutionAllowed(String toolName) {
        if (!allowsQueryExecution()) {
            throw new IllegalStateException(
                    toolName + " is disabled under capability profile " + this
                            + " - requires SAFE_QUERY or ADVANCED_QUERY");
        }
    }

    /**
     * Narrower gate than {@link #requireQueryExecutionAllowed} - explain-plan
     * capture (ADR 0033) is an {@code ADVANCED_QUERY}-only capability per
     * master brief section 6.3, not granted by {@code SAFE_QUERY} the way
     * {@code executeQuery}/{@code lookupRefData} are.
     */
    public void requireExplainPlanAllowed(String toolName) {
        if (this != ADVANCED_QUERY) {
            throw new IllegalStateException(
                    toolName + " is disabled under capability profile " + this + " - requires ADVANCED_QUERY");
        }
    }
}
