package io.github.marekpompura.db2mcp.policy.sql;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Structural complexity limits for executeQuery/explainQuery under
 * SAFE_QUERY (ADR 0034) - deterministic, cheap-to-explain caps distinct
 * from {@link QueryLimitsProperties}'s resource envelope (rows/time/bytes):
 * these bound the *shape* of a query, not how much it can return. Cross
 * joins are rejected unconditionally regardless of these limits (master
 * brief section 7.5 - a categorical policy, not a tunable threshold), so
 * there is no corresponding property for that here.
 */
@Validated
@ConfigurationProperties(prefix = "db2mcp.query-complexity")
public record QueryComplexityLimitsProperties(
        @DefaultValue("5") @Positive int maxJoins, @DefaultValue("3") @Positive int maxNestingDepth) {
}
