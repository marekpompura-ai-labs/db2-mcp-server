package io.github.marekpompura.db2mcp.domain.model;

/**
 * The resource limits actually in effect (database-portability extension
 * section 5.2) - {@code QueryLimitsProperties}'s values, or {@code
 * AdvancedQueryLimitsProperties}'s under the {@code ADVANCED_QUERY}
 * capability profile (ADR 0032), via {@code ResolvedQueryLimits}; never an
 * independently-tracked, driftable copy. Reported even under {@code
 * METADATA_ONLY}, where they're configured but not currently exercised by
 * any registered tool; that's still useful for a client deciding whether to
 * request a more permissive profile. {@code maxJoins}/{@code
 * maxNestingDepth} (ADR 0034) follow the identical pattern, resolved via
 * {@code ResolvedComplexityLimits} - a client can see the active
 * complexity caps before hitting a rejection, not just the row/byte caps.
 */
public record EffectiveLimits(int maxRows, int maxCellBytes, int maxPayloadBytes, int maxJoins, int maxNestingDepth) {
}
