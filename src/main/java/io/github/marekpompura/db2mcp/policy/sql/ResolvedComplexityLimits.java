package io.github.marekpompura.db2mcp.policy.sql;

import io.github.marekpompura.db2mcp.policy.capability.CapabilityProfile;

/**
 * The complexity limits actually in effect for the current capability
 * profile (ADR 0034) - {@link AdvancedQueryComplexityLimitsProperties}'s
 * values under {@code ADVANCED_QUERY}, {@link QueryComplexityLimitsProperties}'s
 * values otherwise. Same shape as {@link ResolvedQueryLimits} (ADR 0032):
 * single source of truth for both {@code SqlComplexityValidator}
 * (enforcement) and {@code Db2CapabilityAdapter} (what {@code
 * getCapabilities} reports).
 */
public record ResolvedComplexityLimits(int maxJoins, int maxNestingDepth) {

    public static ResolvedComplexityLimits of(
            CapabilityProfile profile,
            QueryComplexityLimitsProperties base,
            AdvancedQueryComplexityLimitsProperties advanced) {
        return profile == CapabilityProfile.ADVANCED_QUERY
                ? new ResolvedComplexityLimits(advanced.maxJoins(), advanced.maxNestingDepth())
                : new ResolvedComplexityLimits(base.maxJoins(), base.maxNestingDepth());
    }
}
