# ADR 0032: Give ADVANCED_QUERY a real, differentiated policy

## Status
Accepted

## Date
Proposed 2026-08-19, Accepted 2026-08-19.

## Context
ADR 0006 introduced `CapabilityProfile.ADVANCED_QUERY` as a deliberate
placeholder: "reserved for private-lab experimentation; not currently
different in behavior from `SAFE_QUERY` since no advanced-only capability
exists yet." Every consumer (`McpConfig`'s tool registration,
`Db2QueryAdapter`, `Db2MetadataAdapter`) only ever checked
`allowsQueryExecution()`, which is `true` for both `SAFE_QUERY` and
`ADVANCED_QUERY` alike — genuinely no distinction existed anywhere in the
codebase.

`docs/research/phase5-roadmap.md` (Phase 5 private-research roadmap,
derived from `docs/master-brief.md:1163-1173`) sequences thread 1,
"advanced query policy," as the foundation the roadmap's later threads
(explain-plan acquisition, query complexity/cost controls) need before they
have anywhere real to attach. Master brief §6.3 (verbatim): "Private
experimentation only by default. Broader diagnostic queries. May support
explain-plan experiments or configurable policy exceptions. Must be
explicitly enabled. Must never weaken database-account least privilege."

## Decision drivers
- §6.3's binding constraint — "must never weaken database-account least
  privilege" — rules out anything that grants the application more DB2
  privileges. "Broader" can only mean a larger envelope around the same
  read-only, schema-allowlisted, structurally-validated queries, never a
  weaker guarantee.
- Explain-plan capture (`EffectiveCapabilities.explainPlan`, already a field
  documented as "not implemented" on purpose) is explicitly a separate,
  later roadmap thread, not this one — this ADR does not implement it.
- `Db2QueryAdapter.requireQueryExecutionAllowed` and
  `Db2MetadataAdapter.requireRowDataAccessAllowed` were byte-for-byte
  duplicated capability gates, differing only in the tool name in the
  thrown message — worth deduplicating while both adapters were already
  being touched for this change, not as a separate later cleanup.
- A resolver taking `CapabilityProfile` plus both `*QueryLimitsProperties`
  types cannot live in `domain.model` alongside `EffectiveLimits`:
  `LayeringArchitectureTest.domain_layer_must_not_depend_on_any_other_layer`
  forbids any `..domain..` class from depending on `..policy..`.

## Decision
`ADVANCED_QUERY`'s first real, differentiated behavior is its own,
independently configured query-limits envelope:

1. **`AdvancedQueryLimitsProperties`** (`policy/sql`), bound at
   `db2mcp.advanced-query` — same four-field shape as the existing
   `QueryLimitsProperties` (`maxRows`, `timeoutSeconds`, `maxCellBytes`,
   `maxPayloadBytes`), but a separate, independently defaulted record, not
   an override of the base one. Defaults are more generous
   (`maxRows=5000`, `timeoutSeconds=120`, `maxCellBytes=65536`,
   `maxPayloadBytes=4194304` vs. the base 1000/30/16384/1048576) —
   illustrative and tunable, not derived from load testing (a later,
   separate roadmap thread does that).
2. **`ResolvedQueryLimits`** (`policy/sql`) — a plain record with a static
   `of(CapabilityProfile, QueryLimitsProperties, AdvancedQueryLimitsProperties)`
   factory: returns the advanced record's values under `ADVANCED_QUERY`,
   the base record's values otherwise. Single source of truth, computed
   once per adapter at construction, so `Db2QueryAdapter`'s actual
   enforcement and `Db2CapabilityAdapter`'s reported `getCapabilities`
   manifest can never disagree about which limits are really active.
   Lives in `policy.sql`, not `domain.model`, per the layering constraint
   above.
3. **`EffectiveConfigReporter`** (ADR 0015) now logs the *resolved* limits,
   not always the base ones — an operator who sets `capability-profile:
   ADVANCED_QUERY` and checks the startup log sees the numbers actually in
   effect, not the `SAFE_QUERY` defaults regardless of profile. Leaving
   this unfixed would have quietly undermined the diagnostic trust ADR 0015
   exists to provide.
4. **Capability-gate dedup**: `CapabilityProfile` gained an instance method,
   `requireQueryExecutionAllowed(String toolName)`, replacing both
   adapters' private static duplicates. No test asserted the exception
   message text (only `ErrorCategory.CAPABILITY_DISABLED`), so this was
   safe to fold in alongside the limits work.

What did **not** change: `SqlStructuralValidator` and
`SchemaAllowlistValidator` are untouched — `ADVANCED_QUERY` still only
accepts single-statement `SELECT`/`WITH` queries against allowlisted
schemas, identically to `SAFE_QUERY`. No new DB2 grants. No schema
allowlist relaxation.

## Explicitly out of scope
- Explain-plan acquisition (`EffectiveCapabilities.explainPlan` stays
  `false`) — Phase 5 roadmap thread 2.
- Query complexity/cost thresholds (join count, nesting depth) — Phase 5
  roadmap thread 3; §6.2 already lists this as a `SAFE_QUERY` aspiration
  not yet implemented for either profile.
- The specific numeric defaults above are a starting point, not a verified
  capacity claim — Phase 5 roadmap thread 4 (performance/red-team testing)
  is where they'd actually be load-tested.

## Consequences
- `getCapabilities` now reports genuinely different `limits` under
  `ADVANCED_QUERY` vs. `SAFE_QUERY`/`METADATA_ONLY` — the first real,
  client-visible signal that the profile means something.
- `SAFE_QUERY`/`METADATA_ONLY` behavior is byte-identical to before this
  change (`ResolvedQueryLimits.of` passes the base record's values through
  unchanged for anything but `ADVANCED_QUERY`), so the MCP contract
  snapshots (`mcp-contract-snapshot.json`, `mcp-output-contract-snapshot.json`,
  both captured under a non-`ADVANCED_QUERY` profile) are unaffected.
- Two fewer duplicated methods (`Db2QueryAdapter.requireQueryExecutionAllowed`,
  `Db2MetadataAdapter.requireRowDataAccessAllowed`), one shared enum method
  instead.
- `Db2QueryAdapter`, `Db2CapabilityAdapter`, and `EffectiveConfigReporter`
  each gained one constructor parameter
  (`AdvancedQueryLimitsProperties`) — Spring's `@ConfigurationPropertiesScan`
  picks the new record up automatically, no explicit bean wiring needed.

## Verification / enforcement
`./mvnw verify -DskipITs` (unit tests, ArchUnit — confirms no
`..domain..`→`..policy..` violation from `ResolvedQueryLimits`'s placement,
MCP contract snapshot tests, JaCoCo coverage gate). New unit tests:
`ResolvedQueryLimitsTest` (parameterized over all three profiles),
`CapabilityProfileTest`'s new `requireQueryExecutionAllowed` cases,
`Db2CapabilityAdapterTest` (new, asserts manifest limits differ by
profile), an `EffectiveConfigReporterTest` case asserting the logged
numbers are the advanced ones under `ADVANCED_QUERY` even when the base
record's numbers differ. `scripts/smoke-test.sh` and
`scripts/eof-shutdown-race-test.sh` re-run against the packaged jar,
unmodified, confirming the new constructor parameters didn't break bean
wiring or the ADR 0031 shutdown path.

## Related decisions
ADR 0006 (introduced the placeholder this ADR fills in), ADR 0007 (the
base `SAFE_QUERY` limits this ADR adds a sibling to), ADR 0015 (
`EffectiveConfigReporter`, corrected here to report resolved limits), ADR
0028 (`getCapabilities`/`Db2CapabilityAdapter`, extended here to report
per-profile limits).
