# ADR 0039: Close the three P0 findings from the 2026-09-01 master-brief gap review

## Status
Accepted — implemented and verified in this environment (`mvn verify
-DskipITs` green, 364 tests; packaged-jar smoke test and stdin-EOF
shutdown-race regression script both re-run and passing unmodified).

## Date
Proposed 2026-09-01, Accepted 2026-09-01.

## Context
`docs/reviews/2026-09-01-master-brief-gap-review.md` (ChatGPT, per the
cross-model workflow ADR 0038 formalizes) reported three release-blocking
findings, each independently validated against the actual code before
being accepted — not taken on the review's word — and recorded in
`docs/reviews/2026-09-01-master-brief-gap-review-response.md`:

**Process note, stated plainly rather than glossed over**: this
project's own established practice (issues #26–#33) is to file a GitHub
issue *before* fixing a real bug, not after. That was missed here — the
three fixes below were committed and pushed first, and issues #34/#35/#36
were filed retroactively, after the fact, once the omission was caught.
The issue numbers are cross-referenced below for the sake of a complete
tracking trail, not because the process was followed correctly.

1. **P0-1 — Metadata tools bypassed the configured visibility scope
   (issue #34).**
   `Db2MetadataAdapter.listSchemas()` and six of its seven pure metadata
   methods (`listTables`, `describeTable`, `getTableDependencies`,
   `listProcedures`, `describeProcedure`, `getProcedureSource`) accepted
   any caller-supplied schema with no `SchemaAllowlistValidator` check at
   all — confirmed by grepping for its call sites, which matched only
   `lookupRefData` and `Db2QueryAdapter`'s `executeQuery`/`explainQuery`.
   Under the default `METADATA_ONLY` profile, a client could enumerate
   every non-system schema and describe catalog objects in any of them,
   regardless of `db2mcp.allowed-schemas`.
2. **P0-2 — Configured byte/row limits were not hard and did not cover
   every data-returning tool (issue #35).** `Db2QueryAdapter.executeQuery` exempted
   the first row from its payload cap unconditionally; its byte accounting
   summed per-cell content only, ignoring JSON structural overhead;
   `Db2ValueNormalizer`'s truncation suffix was appended *after* the
   `maxCellBytes` budget rather than counted against it; CLOB truncation
   budgeted by character count, not UTF-8 byte count; `lookupRefData` had
   no cell or payload limit at all and could not report truncation; the
   seven pure metadata methods had neither a row cap nor a query timeout,
   and `getProcedureSource`'s `TEXT` column was fully unbounded.
3. **P0-3 — Sensitive-detail sanitization was incomplete (issue #36).** Every
   validator-failure catch in `Db2QueryAdapter`'s and
   `Db2MetadataAdapter`'s guard chains forwarded `e.getMessage()`
   straight into the client-visible `McpToolException` — `
   SqlStructuralValidator`/`SqlComplexityValidator` embed
   `JSQLParserException.getMessage()`, `SchemaAllowlistValidator` embeds
   the caller's raw table reference/schema, `requireIdentifier` embeds the
   raw identifier. `EffectiveConfigReporter` logged the full datasource
   URL (host/port/database), the complete `allowedSchemas` list, and the
   audit file's filesystem path at INFO; `io.github.marekpompura.db2mcp`
   was unconditionally DEBUG in `logback-spring.xml`, with no profile
   guard, enabling `Db2ConnectivityChecker`'s raw-exception detail log by
   default in every environment.

All three were spot-verified against the running code — for P0-1/P0-2,
via four parallel investigation passes each citing specific file:line
evidence, cross-checked by direct reading before any fix was written; for
P0-3, by independently tracing the exact call chain from each validator's
`throw` site through to `McpToolUtils.toSyncToolSpecification`'s handling
of a thrown `Exception` (decompiled in this project's own prior work for
ADR 0018) to confirm `McpToolException.getMessage()` really is the entire
channel an MCP client sees.

## Decision drivers
- All three are genuine, currently-exploitable gaps under the default
  configuration, not hypothetical — P0-1 in particular directly
  contradicts `METADATA_ONLY`'s stated purpose the same way ADR 0020's
  CR-001 did for `lookupRefData`.
- Fixes must reuse existing machinery (`SchemaAllowlistValidator`,
  `ResolvedQueryLimits`, `Db2ValueNormalizer`, `McpToolException`'s
  category taxonomy) rather than inventing parallel mechanisms per tool.
- Per the review's own suggested instruction and this project's explicit
  scoping decision for this change: close what the three P0 findings
  actually require, not every P1/P2 remediation bullet the review also
  lists (a table/object-level allowlist, named request DTOs, a fully
  byte-exact response envelope including framework wrapper overhead,
  truncation-reporting for every list tool). Those remain open, tracked in
  the response matrix, not silently expanded into this change's scope.
- Governance documentation (ADR 0038, the review and response documents)
  was committed separately from this remediation, per explicit
  instruction — this ADR and its diff contain no governance-document
  changes.

## Decision

### P0-1: schema-scope enforcement on every metadata method
`Db2MetadataAdapter.instrumented` gained a second overload,
`instrumented(String toolName, String schema, String fingerprint,
Supplier<T> work)`, that calls `schemaAllowlistValidator.validateSchema
(schema)` before `work` ever runs — recording `SCHEMA_NOT_ALLOWED` and
throwing `McpToolException(SCOPE_DENIED, ...)` on rejection, identically
to `lookupRefData`'s existing inline check. All six schema-taking methods
now call this overload; `listSchemas` (no single schema to validate)
keeps the original schema-less overload but filters its own result:
`SchemaAllowlistValidator` gained a non-throwing `isAllowed(String)`
(shared internally by `validateSchema`), and `listSchemas` now returns
only `SchemaInfo`s that pass it. An empty `db2mcp.allowed-schemas` — the
default — therefore now means `listSchemas` returns nothing and every
other metadata call is rejected, the same fail-closed default
`executeQuery`/`lookupRefData` already had.

A table/object-level allowlist (the review's broader "no table allowlist
exists today" remediation bullet) is deliberately not added here — a new
capability with no prior design precedent in this codebase, scoped
separately from closing the confirmed schema-visibility bypass.

### P0-2: hard byte/row limits
- `Db2QueryAdapter.executeQuery`'s per-row payload check no longer exempts
  the first row (`if (!rows.isEmpty() && ...)` → `if (...)`) — a budget
  smaller than even one row's content now returns zero rows with
  `truncated: true`, not a silent over-budget response.
- **Superseded by [ADR 0045](0045-hard-universal-payload-bound.md)
  (#62):** the residual gap this bullet states plainly below — the
  enclosing envelope going unmeasured — was later found to mean the bound
  did not actually hold. `JsonPayloadSizer` no longer exists;
  `PayloadBudget` measures the complete serialized response instead. The
  bullet stands as the record of what was decided in September 2026.
- Per-row payload accounting now uses real JSON-serialized byte length
  (new package-private `JsonPayloadSizer`, wrapping a plain Jackson
  `ObjectMapper`) instead of summing `Db2ValueNormalizer.Normalized
  .approximateBytes()` per cell — a deterministic size model including
  brackets/commas/quoting, not an estimate that ignored them. Deliberately
  still doesn't include the enclosing `QueryResult` envelope (column
  descriptors, wrapper keys) — fixed, row-count-independent overhead, not
  chased here; stated plainly as a residual gap in the response matrix,
  not silently assumed closed.
- `Db2ValueNormalizer`'s truncation is now a single shared `bound(String,
  int maxCellBytes, boolean moreContentExists)` primitive: the suffix is
  truncated *into* the same `maxCellBytes` budget it announces, not
  appended on top of it, with a defensive final trim for the pathological
  case where `maxCellBytes` is configured smaller than the suffix text
  itself. `boundedClob` now reads `maxCellBytes + 1` characters (the same
  "+1 to detect truncation without a second query" pattern this project's
  row caps already use) and passes real UTF-8 byte length through `bound`,
  closing the character-vs-byte gap for multi-byte CLOB content. BLOB/
  unsupported-type marker strings now go through `bound` too.
- `Db2MetadataAdapter`'s constructor now takes `AdvancedQueryLimitsProperties`
  and resolves `maxRows`/`timeoutSeconds`/`maxCellBytes`/`maxPayloadBytes`
  via `ResolvedQueryLimits.of(capabilityProfile, ...)` — the same
  abstraction `Db2QueryAdapter` uses — rather than always applying
  `QueryLimitsProperties`'s SAFE_QUERY-tier numbers regardless of active
  profile. This closes P1-1 (the `ADVANCED_QUERY` disagreement) as a
  direct consequence of implementing P0-2 correctly for `lookupRefData`,
  not as separately expanded scope.
- `lookupRefData`'s CODE/DESCRIPTION cells are now bounded via
  `Db2ValueNormalizer.normalize` (through a new `boundedCell` helper), and
  the accumulated entry list is now also payload-byte-capped using the
  same `JsonPayloadSizer` model, with `truncated` now reflecting either
  the row cap or the payload cap. The MCP-facing return type
  (`List<ReferenceDataEntry>`) still has no field to carry a truncation
  signal to the client — that remains open, tracked as part of the
  broader "bounded result envelope for every list tool" redesign the
  review itself scopes separately (comparable in size to P1-2's named
  request/response contracts).
- The shared `jdbcTemplate` backing the six pure metadata list/describe
  methods now has `setMaxRows(maxRows)` and `setQueryTimeout(...)` applied
  (previously neither existed for these methods at all) — exactly
  `maxRows`, not `maxRows + 1`, since `instrumented` doesn't slice its
  result afterward to detect and report truncation for these six methods;
  a `+1` cap with no slicing step would have let one row over the
  configured limit through unnoticed. `getProcedureSource`'s `TEXT` column
  is now bounded through the same `boundedCell` helper `lookupRefData`
  uses.

### P0-3: sanitized error messages and startup logging
- Every validator-failure catch in `Db2QueryAdapter.executeQuery`/
  `explainQuery` (structural validation, schema allowlist, complexity) and
  `Db2MetadataAdapter.lookupRefData` (identifier validation, schema
  allowlist) now throws `McpToolException` with a stable, generic message
  — never `e.getMessage()`. `CAPABILITY_DISABLED` catches are left
  unchanged: `CapabilityProfile`'s exception messages interpolate only a
  fixed tool-name constant and the enum's own name, never caller-supplied
  content, confirmed by reading the class in full.
  `describeProcedure`/`getProcedureSource`'s not-found messages also stay
  as-is — they echo back the schema/procedure name the caller themselves
  supplied, a qualitatively different, lower-risk case than the other
  four (documented in the response matrix, not silently left unexamined).
- `EffectiveConfigReporter` now logs `allowedSchemaCount` (a count, not
  the list), `auditFileConfigured` (a boolean, not the path), and
  `datasourceScheme` (e.g. `jdbc:db2`, not host/port/database) in place of
  the three fields the review flagged. The now-unused `redactCredentials`/
  `CREDENTIALS_IN_URL` (a no-op in practice against this project's own URL
  format, per the class's own prior javadoc) were removed rather than
  left dead.
- `logback-spring.xml`'s `io.github.marekpompura.db2mcp` logger changed
  from unconditional `DEBUG` to `INFO`, matching the root logger. An
  operator who needs DEBUG diagnostics opts in via Spring Boot's own
  standard `logging.level.io.github.marekpompura.db2mcp=DEBUG` property —
  no new project-specific property invented for something the framework
  already provides.
- Structured-JSON operational logging (the review's other §9.1-related
  remediation bullet) is not addressed here — a larger, separate logging-
  format decision, not part of what "sensitive-detail sanitization" P0-3
  specifically names.

## Consequences
- **Verified at the same three levels ADR 0020 established:** unit/
  integration tests (`Db2MetadataAdapterSchemaScopeTest`,
  `Db2MetadataAdapterRowLimitTest`, new/updated cases in
  `Db2ValueNormalizerTest`, `Db2QueryAdapterIntegrationTest`,
  `Db2MetadataAdapterLookupRefDataIntegrationTest`,
  `EffectiveConfigReporterTest`), the packaged jar (`scripts/smoke-test.sh`,
  `scripts/eof-shutdown-race-test.sh`, both re-run and passing unmodified,
  confirming no regression to tool registration or shutdown timing), and
  the full suite (`mvn verify -DskipITs`, 364 tests, JaCoCo policy gate,
  all ArchUnit rules).
- **Regression tests reproduce the pre-fix bugs directly, not just the
  post-fix behavior.** `Db2ValueNormalizerTest
  .normalize_truncatedResult_neverExceedsMaxCellBytesEvenWithTheSuffix`
  and the multi-byte CLOB test in `Db2QueryAdapterIntegrationTest` fail
  against the pre-fix code (confirmed while writing them, before the fix
  landed) and pass against it; the same is true of
  `Db2QueryAdapterIntegrationTest
  .executeQuery_returnsZeroRowsWhenBudgetIsSmallerThanEvenOneRow` (renamed
  from `..._alwaysReturnsAtLeastOneRowEvenOverBudget`, which asserted the
  bug directly) and the sentinel-based P0-3 leakage tests.
- **Behavior changes an operator could notice.** An unconfigured
  (`allowed-schemas: []`) server's metadata tools now return nothing
  rather than every non-system schema — matches the documented,
  already-established fail-closed default for `executeQuery`, but is a
  visible change from the pre-fix behavior. A query whose very first row
  exceeds `maxPayloadBytes` now returns zero rows instead of exactly one.
- **What's still open**, tracked in
  `docs/reviews/2026-09-01-master-brief-gap-review-response.md`, not
  silently closed by this change: a table/object-level allowlist, named/
  validated tool-input contracts (P1-2), a byte-exact response envelope
  including framework wrapper overhead, truncation-reporting on
  `lookupRefData`'s and the other list tools' MCP-facing return shape,
  structured-JSON operational logging, and every other P1/P2 finding this
  change deliberately did not touch.

## Verification / enforcement
`mvn verify -DskipITs` (364 tests, JaCoCo policy-coverage gate, all
ArchUnit layering rules). `scripts/smoke-test.sh` and
`scripts/eof-shutdown-race-test.sh` re-run against the packaged jar,
unmodified, both passing. Contract snapshot tests unchanged (no tool
added or removed, no output shape changed — these fixes are enforcement
and message-content changes, not new fields).

## Related decisions
ADR 0038 (the cross-model review workflow this closure follows — see
`docs/reviews/2026-09-01-master-brief-gap-review.md` and its response
document for the full 13-finding disposition, 12 confirmed and acted on
or explicitly deferred, one sub-claim rejected with evidence). ADR 0020
(the prior P0-closure ADR this one mirrors in structure and discipline).
ADR 0008 (schema allowlist, extended here to the metadata methods). ADR
0023 (response payload/cell-size limits, hardened here). ADR 0018 (the
structured error model this fix's message sanitization stays inside).
ADR 0032 (`ResolvedQueryLimits`, now also used by `Db2MetadataAdapter`).
ADR 0015 (`EffectiveConfigReporter`'s original design, whose logged-field
set this ADR narrows).
