package io.github.marekpompura.db2mcp.policy.sql;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Structural complexity limits under ADVANCED_QUERY (ADR 0034) - a
 * separate, independently configured envelope from {@link
 * QueryComplexityLimitsProperties}'s SAFE_QUERY limits, not an override of
 * them, mirroring {@link AdvancedQueryLimitsProperties}'s own relationship
 * to {@link QueryLimitsProperties} (ADR 0032). Defaults are more generous
 * but illustrative/tunable, not derived from load testing. Cross joins are
 * still rejected unconditionally under this profile too - "broader" never
 * means a weaker guarantee (master brief section 6.3).
 */
@Validated
@ConfigurationProperties(prefix = "db2mcp.advanced-query-complexity")
public record AdvancedQueryComplexityLimitsProperties(
        @DefaultValue("15") @Positive int maxJoins, @DefaultValue("6") @Positive int maxNestingDepth) {
}
