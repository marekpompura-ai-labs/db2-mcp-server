# ADR 0041: Correlation ID propagation and a result-byte metric (#40)

## Status
Accepted

## Date
Proposed 2026-09-06, Accepted 2026-09-06.

## Context
P1-3 of the 2026-09-01 master-brief gap review (`docs/reviews/2026-09-01-master-brief-gap-review.md`,
tracked as issue #40) confirmed observability was not end-to-end, sub-point
by sub-point: `QueryResult`/`ExplainPlanResult` had no `correlationId`
field - only `McpToolException` did, so a *successful* call gave the caller
nothing to correlate against the audit log or a trace; every `span.tag(...)`
call project-wide carried only `tool`, never `correlationId`; `MetricsRecorder`
defined `mcp.tool.calls`, `mcp.tool.duration`, `mcp.tool.rejected`, and
`mcp.result.rows`, but no byte-size meter; there was no explicit
cancellation/timeout counter; and `Db2ConnectivityChecker`'s readiness check
runs once at startup, never re-checkable.

The issue's own text scoped this deliberately rather than asking for all
five sub-gaps closed at once: "correlation ID propagation to spans/results
and a result-byte metric are likely the highest-value, lowest-risk
additions; the readiness/cancellation items may warrant their own follow-up
given ADR 0019's existing trade-off and the query-cancellation issue
respectively." This ADR closes exactly the two named items.

## Decision drivers
- Precedent already existed for exactly this style of small, additive
  observability field: `AuditEvent`'s own `rowCount`/`truncated` fields are
  `Integer`/`Boolean`, not primitives, specifically because a single-object
  result (`describeProcedure`, `getProcedureSource`) legitimately has none
  to report - the same accommodation `correlationId` needed here for
  methods with no natural byte count of their own didn't apply (every
  method that returns data already computes *some* representative byte
  count), but the general "add the field everywhere it's meaningful, not
  just where the review's example happened to point" instinct carried over.
- `docs/reviews/2026-09-01-master-brief-gap-review-response.md`'s own
  finding that `McpOutputContractSnapshotTest` exists specifically to force
  an output-shape change like this through a reviewed diff instead of
  landing silently (CR-008, ADR 0025) - confirmed directly by deleting and
  regenerating the golden file and reviewing the diff, not assumed safe.

## Decision

### `correlationId` added to `QueryResult` and `ExplainPlanResult`
Both records gain a trailing `String correlationId` field (kept last, so
every existing positional-constructor call site only needed one appended
argument, not a full rewrite). `Db2QueryAdapter.executeQuery`/`explainQuery`
already generate a `correlationId` local variable per call (used for audit
lines and thrown exceptions since ADR 0018/0025) - both success paths now
pass that same value into the returned record, rather than generating a
second, unrelated identifier. `Db2QueryAdapter.truncateOperators` (already a
`static`, unit-testable pure function per its own javadoc) gained a
`correlationId` parameter for the same reason. `FakeDatabaseAdapter`
(test-only, database-portability evidence, ADR 0029) generates its own
`UUID.randomUUID()` for `QueryResult.correlationId()`, matching the real
adapter's per-call behavior rather than a fixed placeholder.

`lookupRefData` and the six pure metadata methods (`listTables`,
`describeTable`, `getTableDependencies`, `listProcedures`,
`describeProcedure`, `getProcedureSource`) return bare
`List<T>`/single-object domain types with no existing result envelope -
adding a `correlationId` field there would mean introducing a new wrapper
type around every one of those return shapes, a materially larger and
differently-scoped change the issue never asked for. Their `correlationId`
remains visible only via the audit log line and the root span tag (below),
exactly as the issue's own wording ("QueryResult/ExplainPlanResult records
have no correlationId field") named only these two return types.

### `correlationId` tagged on every root span

> **Correction (2026-09-17 re-review, R2-P1-7), resolved.**
> "Project-wide" below originally overstated it: `Db2CapabilityAdapter`
> tagged its root span with `tool` only and was missed by this change.
> Closed under #94 in two steps - first the span tag and the result-byte
> metric, then a `correlationId` field on `CapabilityManifest` so the id
> reaches the client as it does for every other tool. The claim below is
> now true without exception.

Every `Span.tag("tool", ...)` call project-wide (`Db2QueryAdapter.executeQuery`/
`explainQuery`, `Db2MetadataAdapter.lookupRefData` and its shared
`instrumented` helper backing the other six metadata methods,
`AdmissionControlledToolCallback`'s concurrency-rejection span) now also
tags `correlationId` immediately alongside it - five call sites total, all
already holding a `correlationId` local variable before this change, so
each edit is a single added line. A trace can now be tied back to a
specific audit log line or client-visible error by this one shared field,
closing the review's "spans never carry correlationId" finding for every
span this codebase creates, not just `executeQuery`'s.

### New `mcp.result.bytes` metric (`MetricsRecorder.recordResultBytes`)
A `DistributionSummary`, tagged by `tool`, mirroring `recordRows`'s exact
shape. Recorded from the caller's own already-computed byte total, not
recomputed inside `MetricsRecorder` itself:
- `executeQuery`: the running `payloadBytes` total already enforced against
  `maxPayloadBytes` (P0-2, ADR 0039).
- `lookupRefData`: the equivalent running `payloadBytes` total P0-2 added
  there.
- `explainQuery`: no existing byte budget exists for explain output (only a
  row/operator cap) - `JsonPayloadSizer.byteLength(result.operators())` is
  computed fresh here specifically for this metric, the same real
  JSON-serialized accounting P0-2 uses everywhere else in this codebase,
  not an approximation.
- The six pure metadata methods, via the shared `instrumented` helper:
  `JsonPayloadSizer.byteLength(result)` on the generic `T result`, so this
  metric fires for every tool that returns data, not only the three the
  review's example happened to name.

Recorded only on the success path, matching `recordRows`'s own placement
(the same "no result to size" reasoning P0-2 already established for
these methods' complete absence of any byte accounting before this
issue).

## Explicitly out of scope
- **`AuditEvent.traceId`/`spanId` fields.** The review's own text separates
  "correlation ID propagation" from this specific sub-point; adding real
  OpenTelemetry trace/span IDs to the audit trail would require pulling
  values off the current `Span`/`TraceContext` via Micrometer Tracing's
  API - unverified here, and a materially different, larger change than the
  two items the issue explicitly recommended as "highest-value,
  lowest-risk." `AuditEvent` already carries `correlationId` (unchanged by
  this issue) as its own cross-reference key.
- **An explicit cancellation/timeout counter or `PolicyDecisionReason`
  value.** The issue's own text defers this to "the query-cancellation
  issue" - #46 ("Query cancellation relies only on driver timeout, no
  explicit server-initiated `Statement.cancel()` path"). Adding an
  observability counter for a capability that doesn't exist yet would be
  premature.
- **Making `Db2ConnectivityChecker`'s readiness re-checkable.** The issue's
  own text defers this to ADR 0019's already-documented, deliberate
  zero-network-exposure trade-off - reopening it wasn't asked for here and
  isn't reopened.

## Consequences
- A successful `executeQuery`/`explainQuery` call now returns the same
  `correlationId` its own audit log line and every span in its call tree
  carry - confirmed by directly comparing the returned value against the
  audit log's own parsed correlation id, not merely asserting both are
  non-null.
- No change to the MCP tool *input* contract - `McpContractSnapshotTest`
  passes with no diff (this issue never touches a `@Tool` method's
  parameters). The *output* contract for `executeQuery`/`explainQuery`
  changed deliberately - `McpOutputContractSnapshotTest`'s golden file
  gained exactly one field (`correlationId`) on exactly those two tool
  outputs, reviewed as a diff before being committed, not silently
  regenerated.
- `mcp.result.bytes` is now observable for every tool that returns data
  (9 of 11; `getCapabilities` and the concurrency-admission-rejection path
  have no result to size).
- Test suite gained 4 new test methods and extended 5 existing ones with
  additional assertions rather than duplicating setup; full suite grew from
  (this branch's `devel` baseline) to 391 tests.

## Verification / enforcement
`./mvnw verify -DskipITs` - full suite green, ArchUnit unchanged (no new
package, no new layering shape), JaCoCo `policy.*` gate unaffected (this
issue's new code lives in `domain.model`/`adapter.db2`/`adapter.observability`/
`configuration`, not `policy.*`). `McpContractSnapshotTest` confirmed zero
diff; `McpOutputContractSnapshotTest`'s diff reviewed directly (delete
golden file, regenerate, inspect the diff) before committing the new
snapshot. `scripts/smoke-test.sh` re-run against the packaged jar,
unmodified assertions still passing.

**Genuinely not verifiable from this environment**, stated plainly per ADR
0009's established honesty framing: the packaged-jar smoke test's DB2 host
is deliberately unreachable, so `mcp.result.bytes`/`correlationId`-on-a-real-
successful-DB2-call can only be demonstrated against H2 (Tier 1, this
issue's own test suite) or asserted structurally (Tier 2's `instrumented`
helper, exercised here via a synthetic `SYSCAT.PROCEDURES` catalog) - not
against a real DB2 instance in this session.

## Related decisions
ADR 0010 (`AuditEvent`'s own `correlationId`, unchanged here), ADR 0011
(`MetricsRecorder`, extended with `recordResultBytes`), ADR 0012 (span
instrumentation, every root span now also tagged `correlationId`), ADR 0018
(`McpToolException`'s pre-existing `correlationId`, the asymmetry this issue
closes for the success path), ADR 0025 (CR-012, the correlation-id-for-
failed-calls precedent this issue extends to successful ones), ADR 0025's
sibling CR-008 (`McpOutputContractSnapshotTest`, the mechanism that forced
this issue's output-shape change through a reviewed diff), ADR 0039 (P0-2's
real-JSON-byte-length model `recordResultBytes` reuses via `JsonPayloadSizer`,
and the `instrumented` helper this issue also extends). Both the sizer and
what `recordResultBytes` is given were later replaced by
[ADR 0045](0045-hard-universal-payload-bound.md) (#62): it now reports the
measured length of the complete response, not of the rows/operators alone.
