package io.github.marekpompura.db2mcp.policy.sql;

import io.github.marekpompura.db2mcp.policy.capability.CapabilityProfile;

/**
 * The query limits actually in effect for the current capability profile
 * (ADR 0032) - {@link AdvancedQueryLimitsProperties}'s values under {@code
 * ADVANCED_QUERY}, {@link QueryLimitsProperties}'s values otherwise. Single
 * source of truth for both {@code Db2QueryAdapter} (enforcement) and {@code
 * Db2CapabilityAdapter} (what {@code getCapabilities} reports), so the two
 * can never drift apart on which limits are "really" active.
 *
 * <p>Distinct from {@code domain.model.EffectiveLimits}: this type carries
 * {@code timeoutSeconds} (needed to configure a {@code JdbcTemplate}, never
 * reported in the MCP-facing manifest) and lives in {@code policy}, not
 * {@code domain}, since resolving it requires {@link CapabilityProfile} and
 * both {@code *QueryLimitsProperties} types - a dependency {@code domain}
 * classes are not allowed to have (see {@code LayeringArchitectureTest}).
 */
public record ResolvedQueryLimits(int maxRows, int timeoutSeconds, int maxCellBytes, int maxPayloadBytes) {

    public static ResolvedQueryLimits of(
            CapabilityProfile profile, QueryLimitsProperties base, AdvancedQueryLimitsProperties advanced) {
        return profile == CapabilityProfile.ADVANCED_QUERY
                ? new ResolvedQueryLimits(
                        advanced.maxRows(), advanced.timeoutSeconds(), advanced.maxCellBytes(), advanced.maxPayloadBytes())
                : new ResolvedQueryLimits(base.maxRows(), base.timeoutSeconds(), base.maxCellBytes(), base.maxPayloadBytes());
    }
}
