package io.github.marekpompura.db2mcp.domain.model;

/**
 * What this server actually does, right now, under its effective
 * capability profile - not what the code is theoretically capable of
 * (database-portability extension section 5.2, ADR 0028). {@code
 * readOnlyQuery} and {@code referenceDataLookup} are {@code false} under
 * {@code METADATA_ONLY}, matching those two tools' own real absence from
 * MCP tool discovery (ADR 0006, ADR 0020) - a client must be able to trust
 * this manifest without separately probing which tools are registered.
 * {@code explainPlan} is {@code true} only under {@code ADVANCED_QUERY}
 * (ADR 0033) - narrower than {@code readOnlyQuery}/{@code
 * referenceDataLookup}, which both query-capable profiles grant.
 */
public record EffectiveCapabilities(
        boolean schemaDiscovery,
        boolean tableDiscovery,
        boolean tableDescription,
        boolean foreignKeyDependencies,
        boolean routineDiscovery,
        boolean routineSource,
        boolean referenceDataLookup,
        boolean readOnlyQuery,
        boolean explainPlan) {
}
