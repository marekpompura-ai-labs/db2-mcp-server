# ADR 0025: Output contract snapshot, all-tool observability, and client-visible correlation IDs

## Status
Accepted — implemented and verified in this environment (`mvn verify`
green, 159 tests, up from 153; a real audit line's `correlationId`
proven to match the thrown exception's, not just both existing
independently; the packaged jar's live MCP response confirmed to carry
`correlationId` for real).

## Date
Proposed 2026-08-15, Accepted 2026-08-15.

## Context
CR-008, CR-009, and CR-012 from the 2026-08-14 external code review,
grouped together in the review's own recommended order ("extend
contract protection and all-tool observability") since they touch the
same code paths:

1. **CR-008 — the MCP contract snapshot (ADR 0013) only pins tool
   *input* schemas.** A change to `QueryResult`, `ColumnInfo`,
   `ProcedureInfo`, or any other result type is not detected as a
   contract change. Concretely real, not hypothetical: ADR 0022 added
   `specificName` to `ProcedureInfo` and `ProcedureSource` a day before
   this ADR, and nothing but manual review caught that it was a
   deliberate change rather than an accident - the existing snapshot
   test is structurally incapable of catching it, since it never
   serializes a tool's actual return value.
2. **CR-009 — observability is implemented only for `executeQuery`
   and `lookupRefData`.** ADR 0020 gave every `Db2MetadataAdapter`
   method sanitized errors, but only `lookupRefData` got audit
   logging, metrics, and tracing spans. The other seven methods
   (`listSchemas`, `listTables`, `describeTable`,
   `getTableDependencies`, `listProcedures`, `describeProcedure`,
   `getProcedureSource`) left no trace anywhere - a call to
   `describeTable` produced no audit line, no metric, no span,
   contradicting the master brief's end-to-end traceability
   requirement, which applies to every tool, not only the two
   row-data ones.
3. **CR-012 — correlation IDs are internal only.** `executeQuery`
   generated a UUID for its own audit line but never returned it to
   the caller. A caller reporting "tool call X failed" had no ID to
   hand back for finding the matching server-side audit record.

## Decision drivers
- CR-009's fix must not duplicate `Db2QueryAdapter`'s and
  `lookupRefData`'s existing pattern seven more times by hand - a
  shared helper, matching the `sanitizeErrors` → `instrumented`
  evolution already used for CR-002.
- The seven pure metadata methods have no capability or schema gate
  (they're visible under every profile) - their only real outcomes are
  `ALLOWED` and `EXECUTION_FAILED`, not the full five-value
  `PolicyDecisionReason` range `executeQuery`/`lookupRefData` need.
- `describeProcedure`/`getProcedureSource`'s "no such procedure" case
  was, before this pass, an unwrapped `IllegalArgumentException` that
  bypassed both sanitization and the (now-added) audit trail - closing
  CR-009 properly meant closing this too, not leaving one outcome
  unaudited while fixing the rest.
- CR-012's fix must be the smallest one that solves the stated problem
  (a caller can't hand back an ID). Rejected: a full result/error
  envelope wrapping every tool's return type - CR-005/ADR 0023 already
  decided against a bigger `QueryResult` shape change for a similar
  reason, and the review's own wording offers propagating the existing
  correlation ID as an equally valid alternative to a new envelope.
- CR-008 must prove it can fail before being trusted, the same as
  every other quality gate in this project.

## Considered options
1. **CR-009: a shared `instrumented(toolName, fingerprint, work)`
   helper inside `Db2MetadataAdapter`, replacing `sanitizeErrors`.**
   Chosen. `Db2QueryAdapter`'s own capability/scope/execution stages
   stay separate (it has all five `PolicyDecisionReason` outcomes to
   handle); the seven metadata methods only need ALLOWED/
   EXECUTION_FAILED, so one helper covers all seven without forcing an
   unused three-stage guard chain onto methods that have no guards.
2. **CR-012: add `correlationId` as a fourth field on
   `McpToolException`'s existing JSON error body, not a new
   success-path envelope.** Chosen. Purely additive to the one place
   the review's own wording specifically named as the actual complaint
   ("a caller reporting a failed tool call cannot provide the
   server-side correlation ID") - success-path results are unaffected,
   so no MCP contract *input* snapshot change was needed for this part
   either.
3. **CR-008: a second golden-snapshot test
   (`McpOutputContractSnapshotTest`), one representative instance of
   each tool's actual return type, serialized and compared exactly
   like `McpContractSnapshotTest` already does for input schemas.**
   Chosen - reuses a pattern already trusted in this codebase rather
   than inventing a different mechanism for the output half of the
   same contract.

## Decision
`Db2MetadataAdapter.instrumented` now wraps all seven pure metadata
methods: generates a correlation ID, opens a root span
(`mcp.tool <name>`) with a `db2.execute` child span, records an
`ALLOWED` audit/metrics outcome on success (row count derived from the
result when it's a `List`, `null` for the two single-object results),
and records `EXECUTION_FAILED` plus a sanitized `McpToolException` on
`DataAccessException`. A new internal `NotFoundException` lets
`describeProcedure`/`getProcedureSource`'s "no such procedure" case
flow through the same instrumented path, rendered to the client as
`ErrorCategory.DB2_OBJECT_NOT_FOUND` rather than the generic
execution-failure category. Fingerprints follow the existing
`LABEL[SCHEMA.OBJECT]` convention (`TABLES[ITSM]`,
`PROCEDURE[ITSM.SQL...]`, etc.) via a generalized
`fingerprintOf(label, parts...)`.

`McpToolException` gained a `correlationId` field (fourth constructor
parameter, backward-compatible 2-arg/3-arg overloads still available)
threaded through every throw site in both `Db2QueryAdapter` and
`Db2MetadataAdapter` - all nine tools' error responses now carry the
same ID their audit line does.

`McpOutputContractSnapshotTest` serializes one instance each of
`SchemaInfo`, `TableInfo`, `ColumnInfo`, `TableDependencyInfo`,
`ProcedureInfo`, `ProcedureDetail`, `ProcedureSource`,
`ReferenceDataEntry`, and `QueryResult`, keyed by tool name, into
`src/test/resources/mcp-output-contract-snapshot.json`.

## Consequences
- **Verified that the correlation ID genuinely links exception to audit
  line, not just that both happen to exist.**
  `executeQuery_exceptionCorrelationIdMatchesTheAuditLogLine` and
  `listSchemas_writesAuditEventWithCorrelationIdMatchingTheThrownException`
  both capture the real audit log line and the real thrown exception
  from the same call and assert the IDs are equal - and confirmed live
  against the packaged jar's actual MCP response:
  `{"errorCode":"DB2_EXECUTION_FAILED","message":"listSchemas query
  failed","sqlState":"08001","correlationId":"b75d9347-..."}`.
- **A real test-design mistake caught while writing this, not shipped
  undiscovered**: the first version of `listTables_writesRealAuditEvent
  OnSuccess` assumed `listTables` could succeed against the H2 fixture.
  It cannot - `listTables` queries `SYSCAT.TABLES`, which has no H2
  equivalent (ADR 0009), so the test always throws and the assertion
  was wrong on its face. Fixed by testing the one outcome actually
  exercisable in this environment (`EXECUTION_FAILED`), which still
  proves the instrumentation works for a second method beyond
  `listSchemas`, not silently deleted or weakened to pass.
- **`McpOutputContractSnapshotTest` proven able to fail, not just
  observed to pass**: a deliberately altered field value in the golden
  file correctly failed the comparison with a readable diff; restored
  and reconfirmed green.
- **No MCP *input* contract snapshot change** - CR-009/CR-012 are
  purely internal/error-body changes; only the new output snapshot
  file is new. `McpContractSnapshotTest` (ADR 0013) is unaffected and
  still green.
- **Full suite: 159 tests** (up from 153) - 6 new: 2 correlation-ID
  matching tests, 1 metadata-audit-on-failure test (replacing the
  flawed success-path test), 2 `McpToolExceptionTest` correlation-ID
  cases, 1 output contract snapshot test. All green under `mvn verify
  -DskipITs`, JaCoCo gate and ArchUnit layering rules included.
  Packaged jar re-verified end to end.

## Verification / enforcement
`mvn verify -DskipITs` (159 tests). `McpOutputContractSnapshotTest`
proven able to fail via a deliberate golden-file edit. Packaged jar's
live MCP session (`scripts/smoke-test.sh`) confirmed `correlationId`
present in a genuine `tools/call` error response.

## Related decisions
ADR 0013 (MCP contract snapshot - the input-side twin this ADR's
output snapshot completes), ADR 0018 (structured error model -
`McpToolException`'s JSON body, now with a fourth field), ADR 0020
(sanitized errors for all metadata tools - CR-009 extends the same
methods with the audit/metrics/span treatment CR-002 didn't add), ADR
0010/0011/0012 (traceability/metrics/spans - the machinery `instrumented`
reuses rather than reinvents), ADR 0021 (the adopted portability
extension - this ADR closes item 7 of the same review's recommended
order).
