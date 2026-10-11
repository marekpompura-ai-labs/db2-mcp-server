package io.github.marekpompura.db2mcp.domain.model;

/**
 * The full {@code getCapabilities} result (database-portability extension
 * section 5.2, ADR 0028): a versioned, operational-metadata-only manifest a
 * client can use to discover what this server actually supports without
 * probing each tool individually or assuming Db2-specific behavior is
 * universal. {@code contractVersion} follows the extension's own
 * versioning rules (section 13) - "1.0" is this manifest shape's first
 * version; a future breaking change (removed/renamed field, changed
 * semantics) requires bumping it, an additive optional field does not.
 *
 * <p>{@code correlationId} (#94, R2-P1-7 of the 2026-09-17 re-review) is
 * the same id this call's audit line and root span carry, matching {@link
 * QueryResult}, {@link ExplainPlanResult} and {@link BoundedList}. Until
 * it was added, {@code getCapabilities} was the one tool a caller could
 * not correlate with server-side records, which made this project's "all
 * eleven tools share the same traceability" claim untrue.
 *
 * <p>Deliberately no {@code contractVersion} bump: this is an additive
 * field, and the versioning rule stated above says a breaking change
 * (removed/renamed field, changed semantics) requires a bump while an
 * additive one does not.
 */
public record CapabilityManifest(
        String contractVersion,
        DatabaseDescriptor database,
        EffectiveCapabilities capabilities,
        EffectiveLimits limits,
        String correlationId) {
}
