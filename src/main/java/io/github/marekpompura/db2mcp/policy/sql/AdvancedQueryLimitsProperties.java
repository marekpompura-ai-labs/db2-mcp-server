package io.github.marekpompura.db2mcp.policy.sql;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Resource limits for executeQuery/lookupRefData under the {@code
 * ADVANCED_QUERY} capability profile (ADR 0032) - a separate, independently
 * configured envelope from {@link QueryLimitsProperties}'s {@code
 * SAFE_QUERY} limits, not an override of them. Only ever consulted when
 * {@code ADVANCED_QUERY} is the active profile, via {@link
 * ResolvedQueryLimits#of}; {@code SAFE_QUERY}/{@code METADATA_ONLY} never
 * read this record.
 *
 * <p>Defaults are deliberately more generous than {@link
 * QueryLimitsProperties}'s (master brief 6.3: "broader diagnostic
 * queries"), but illustrative and tunable, not derived from load testing -
 * that's a later, separate roadmap thread (performance and red-team
 * testing). They grant no additional DB2 privileges and don't relax schema
 * allowlisting or SQL structural validation, so "broader" here means a
 * larger row/time/byte envelope around the same read-only, validated
 * queries, never a weaker guarantee (master brief 6.3: "must never weaken
 * database-account least privilege").
 */
@Validated
@ConfigurationProperties(prefix = "db2mcp.advanced-query")
public record AdvancedQueryLimitsProperties(
        @DefaultValue("5000") @Positive int maxRows,
        @DefaultValue("120") @Positive int timeoutSeconds,
        @DefaultValue("65536") @Positive int maxCellBytes,
        @DefaultValue("4194304") @Positive int maxPayloadBytes) {
}
