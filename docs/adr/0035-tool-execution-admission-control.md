# ADR 0035: Performance and red-team testing (thread 4) - tool-execution admission control, and two SQL policy gaps closed

## Status
Accepted

## Date
Proposed 2026-08-27, Accepted 2026-08-27.

## Context
`docs/research/phase5-roadmap.md` sequences this as thread 4, "performance
and red-team testing," right after threads 1-3 (ADR 0032/0033/0034),
specifically so its coverage exercises the new explain-plan and complexity-
control surfaces, not just what was already shipped. The roadmap originally
framed thread 4 as "test-only work... no new ADR expected." That framing
turned out to be wrong: research into the thread's two stated concerns
surfaced three concrete, previously undiscovered gaps, and the user, given
each one directly, chose to fix all three rather than merely document them.

**Gap 1 - master brief section 7.5's "bound concurrent tool executions" was
declared, never implemented.** No semaphore, executor cap, or admission
gate existed anywhere in the codebase. `InFlightRequestTracker` (ADR 0031)
tracks which JSON-RPC request ids are awaiting a response, purely for
graceful-shutdown draining - it never throttles or rejects anything. Hikari's
own `maximum-pool-size` (5) bounds DB2 connections, but nothing bounded
*tool* executions as a whole, including non-DB2 tools like `getCapabilities`.

**Gap 2 - a join with a structurally tautological `ON` clause defeats
`SqlComplexityValidator`'s cross-join rejection.** `ComplexityWalker.
visitJoins` only checked `Join.isCross() || Join.isSimple()` - an explicit
`JOIN ... ON 1=1` (or `ON TRUE`, or `ON t.x = t.x`) produces exactly the same
unrestricted Cartesian product as a cross join, but is a syntactically normal
join with an `ON` clause, so it passed unchecked. Notably, ADR 0034's own
Consequences section *relied on this gap staying open*: the cross-join
timeout test in `Db2QueryAdapterIntegrationTest` used this exact shape to
slip past the complexity gate and still reach DB2's JDBC timeout.

**Gap 3 - piped SQL syntax bypasses three already-`Accepted` ADRs at once.**
Found during planning for this thread, then independently re-verified by
compiling and running JSqlParser 5.3 directly against the pinned dependency
jar (not assumed from documentation): `CCJSqlParserUtil.parse("SELECT * FROM
T |> CROSS JOIN OTHER_SCHEMA.SECRET")` returns a `net.sf.jsqlparser.
statement.piped.FromQuery` that **is** `instanceof Select` - so it passes
`SqlStructuralValidator`'s type check (ADR 0007). `TablesNamesFinder`
returns an **empty list** for it, so `SchemaAllowlistValidator` (ADR 0008)
finds nothing to check and passes it trivially. `SqlComplexityValidator`'s
`FromQuery` visit method (ADR 0034) is a documented no-op. This syntax
therefore bypassed cross-join rejection, schema allowlisting, and complexity
limits simultaneously - a live gap in three already-shipped, already-pushed
guarantees, not a hypothetical one.

Also delivered by this thread: `HikariPoolConcurrencyIntegrationTest`, an
in-process JUnit test proving what actually happens to a caller when
concurrent `executeQuery` calls exceed the Hikari pool size - queuing
bounded by `connection-timeout`, or a sanitized connection-acquisition
failure - closing the gap that nothing previously exercised this under real
contention (only single-query timeout behavior had a test).

## Decision drivers
- Master brief section 7.3's determinism gate ("consider query-cost
  controls only if they are deterministic enough to explain and test")
  applies to the tautological-`ON` detector exactly as it did to ADR 0034's
  original complexity checks - ruling out anything resembling a SAT solver
  in favor of simple, structural, explainable detection.
- This codebase's established fail-fast philosophy (ADR 0007/0018/0034:
  deterministic rejection over queuing) argued for a non-blocking
  `tryAcquire()` admission gate, deliberately contrasted with Hikari's own
  queue-then-timeout behavior - which `HikariPoolConcurrencyIntegrationTest`
  now demonstrates directly, so the contrast isn't just asserted, it's shown.
- "Broader never means weaker" (the same principle ADR 0032/0034 already
  applied to `ADVANCED_QUERY`) meant the tautological-`ON` and piped-SQL
  rejections had to be unconditional across every profile, not configurable
  thresholds.
- One shared choke point for admission control, not a semaphore-acquire call
  duplicated into every adapter method - `master brief section 7.5` says
  "concurrent tool *executions*," not "concurrent queries," so the gate had
  to cover every registered tool (`getCapabilities` included), which ruled
  out putting it inside `Db2QueryAdapter` alone.

## Decision

### 1. Tautological-ON rejection (`SqlComplexityValidator`)
A new `ComplexityWalker.isTautological(Expression)` static helper, checked
against every join's `ON` expression right after the existing cross-join
check: an `AndExpression` is tautological only if **both** conjuncts are
(so `ON 1=1 AND c.id=t.id` - a real, if inelegant, restricting predicate -
is correctly accepted, not flagged); a `BooleanValue` is tautological iff
its literal value is `true`; an `EqualsTo` is tautological iff both sides
render identical text (`a.N = a.N`, `1=1`). `OR` is deliberately **not**
decomposed - a narrow, honest residual gap (see Explicitly out of scope).
Runs unconditionally under every profile, matching the cross-join check's
own placement.

`Db2QueryAdapterIntegrationTest`'s cross-join timeout test was rewritten to
use `ON a.N > 0` (a `GreaterThan` against a literal, not a structural
tautology) instead of `ON a.N = a.N` - still fully non-selective (every row
in `BULK_ROWS` has `N > 0`), so the test still forces the same real JDBC
timeout, but no longer relies on a gap this ADR closes.

### 2. Piped-SQL rejection (`SqlStructuralValidator`, `SqlComplexityValidator`)
Both validators now explicitly reject `instanceof FromQuery` immediately
after their existing `instanceof Select` check succeeds - the same
fail-closed treatment `SqlStructuralValidator` already gives `FINAL TABLE`/
`OLD TABLE`/`NEW TABLE` (ADR 0007) for a DB2-dialect shape this server
doesn't support, not a semantic analysis of what a piped query does. Added
to both validators independently, not just relying on `SqlStructuralValidator`
running first in `Db2QueryAdapter`'s guard chain, since `SqlComplexityValidator`
is also directly unit-tested and independently callable - the same
"genuinely independent layer" reasoning ADR 0007's own multi-statement gap
fix used.

### 3. Tool-execution admission control (master brief section 7.5)
New `ConcurrencyLimitsProperties` (`policy/concurrency`, prefix
`db2mcp.concurrency`, field `maxConcurrentToolExecutions`, default 10 - set
modestly above Hikari's `maximum-pool-size` of 5 since non-DB2 tools also
count toward it, illustrative and not load-tested, same honesty framing ADR
0032/0034 already used for their own defaults). New
`ToolExecutionAdmissionGate` (`policy/concurrency`), a thin `@Component`
wrapper around `java.util.concurrent.Semaphore`: `tryAcquire()`
(non-blocking) and `release()`.

New `AdmissionControlledToolCallback` (`configuration`), a package-private
decorator implementing Spring AI's `ToolCallback` interface. Only its
`call(String)` override enforces the gate; the interface's default
`call(String, ToolContext)` already delegates to `this.call(input)`
(confirmed by decompiling this project's pinned Spring AI dependency), so it
is automatically routed through the gate too without a second override. On
rejection: `McpToolException(ErrorCategory.POLICY_REJECTED, ...)` - an enum
value that existed since ADR 0018 but had no caller until now - plus an
audit line (new `PolicyDecisionReason.CONCURRENCY_LIMIT_EXCEEDED`), a
`mcp.tool.rejected`/`security.policy.decisions` metric, and a span tagged
`outcome=rejected`, matching the exact audit/metrics/span triple every other
gate in this codebase already uses. The permit is released in a `finally`
block, unconditionally, regardless of whether the delegate succeeds, throws
its own `McpToolException`, or throws anything else - a leaked permit here
would permanently shrink the server's effective concurrency bound for the
rest of the process's life, so this property has dedicated test coverage.

`McpConfig.schemaToolCallbackProvider` wraps every `ToolCallback` the
existing `MethodToolCallbackProvider` produces before returning them - one
shared choke point covering every registered tool (`getCapabilities`,
`executeQuery`, `explainQuery`, `lookupRefData`, every `SchemaTools` method)
regardless of capability profile, rather than a semaphore-acquire call
duplicated into every adapter method. No adapter code changed: this is a
strictly additive outer gate, and every existing capability/structural/
schema/complexity gate still runs exactly as before, for calls that get
past this one.

## Explicitly out of scope
- **OR-decomposition of tautological predicates.** `ON 1=1 OR c.id=t.id` is
  genuinely unrestricted (the `OR` makes the whole predicate always true
  whenever the tautological branch is), but `isTautological` does not
  decompose `OR` - a narrow, deliberate residual gap, same honesty style as
  ADR 0034's UDF-visibility gap.
- **The breadth-bomb gap** (many sibling scalar subqueries or `UNION ALL`
  branches, each individually within `maxNestingDepth`/`maxJoins`, uncapped
  in aggregate) - identified during this thread's research but deliberately
  deferred, not fixed. Closing it risks conflicting with
  `SqlComplexityValidatorTest`'s existing CTE/UNION-sibling non-accumulation
  tests (which deliberately assert siblings do *not* accumulate depth), and
  this thread already carries three production changes; cleaner as its own
  focused follow-up thread.
- **JDBC-level query cancellation-on-demand** - a separate master brief
  section 7.5 bullet ("provide cancellation where the JDBC driver
  supports it"), distinct from the timeout-based cancellation already in
  place via `Statement.setQueryTimeout` (ADR 0007).
- **Reporting the concurrency bound via `getCapabilities`/`EffectiveLimits`**
  - not requested for this thread, kept minimal; `QueryComplexityLimitsProperties`
  (ADR 0034) was similarly never added to `EffectiveConfigReporter`, an
  established precedent for leaving newer `db2mcp.*` properties records out
  of that reporter unless a client-visibility need is identified separately.
- **`explainQuery`'s `EXPLAIN_TIME` correlation race** (ADR 0033's own
  admitted gap: a global `MAX(EXPLAIN_TIME)` read-back, not scoped by
  requester/session, that could return a concurrent caller's plan instead
  of the caller's own). Investigated during this thread's research but not
  addressed - it is only genuinely testable against live DB2 (ADR 0009),
  which is unavailable in this environment; documented as a known gap in
  `docs/security/adversarial-test-cases.md` rather than fabricated as a test
  that can't actually exercise the race.

## Consequences
- A caller can no longer defeat cross-join rejection with a trivially-true
  `ON` clause, or bypass cross-join/schema-allowlist/complexity enforcement
  entirely with piped SQL syntax - both closed for every profile, including
  `ADVANCED_QUERY`.
- Every MCP tool call is now bounded by `db2mcp.concurrency.
  max-concurrent-tool-executions` (default 10) - the first genuine
  implementation of master brief section 7.5's "bound concurrent tool
  executions," previously declared only.
- `ErrorCategory.POLICY_REJECTED` and `PolicyDecisionReason.
  CONCURRENCY_LIMIT_EXCEEDED` are both now genuinely observable in practice,
  not just reserved enum values.
- No change to the MCP tool contract: `McpContractSnapshotTest`/
  `McpOutputContractSnapshotTest` both pass with **no diff** - tools are
  wrapped, not added, removed, or changed in shape.
- `Db2QueryAdapterIntegrationTest`'s cross-join timeout test's `ON` clause
  changed (see Decision 1) - a deliberate fix, not a regression; it still
  proves the same JDBC-level timeout behavior.
- Full test suite grew from 259 (after thread 3) to 287 tests.

## Verification / enforcement
`./mvnw verify -DskipITs` - full unit suite (including
`SqlComplexityValidatorTest`'s and `SqlStructuralValidatorTest`'s new
tautological-ON/piped-SQL cases, `HikariPoolConcurrencyIntegrationTest`,
`ToolExecutionAdmissionGateTest`, `AdmissionControlledToolCallbackTest`,
updated `McpConfigTest`), ArchUnit (no `..domain..`→`..policy..`
violation from `policy.concurrency`'s placement; `configuration`'s existing
dependency on `adapter.observability` already covers
`AdmissionControlledToolCallback`'s needs), JaCoCo `policy.*` coverage gate
(now also covering `policy.concurrency`), MCP contract snapshots (confirmed
no diff, not assumed). `scripts/smoke-test.sh` and
`scripts/eof-shutdown-race-test.sh` re-run against the packaged jar,
confirming the new constructor parameters didn't break bean wiring. Manual
verification against the packaged jar (DB2 host deliberately unreachable, so
a validation/policy rejection is distinguishable from a downstream DB2
failure by error code alone): `ON 1=1`/`ON TRUE` rejected with
`VALIDATION_FAILED`; `ON 1=1 AND <real predicate>` correctly accepted
(reaches DB2); piped SQL rejected with `VALIDATION_FAILED`; a burst of
concurrent `executeQuery` calls against a `max-concurrent-tool-executions`
of 3 produced a mix of successful (DB2-reaching) and `POLICY_REJECTED`
calls, with matching `CONCURRENCY_LIMIT_EXCEEDED` entries in the audit log,
`mcp.tool.rejected`/`security.policy.decisions` metrics, and span tags.

## Related decisions
ADR 0007 (structural validation this ADR extends with piped-SQL rejection),
ADR 0008 (schema allowlist, also bypassed by the piped-SQL gap), ADR 0009
(H2-vs-live-DB2 verification tiers - why the `explainQuery` race is
documented, not tested, here), ADR 0018 (`ErrorCategory`/`McpToolException`,
giving `POLICY_REJECTED` its first caller), ADR 0031
(`InFlightRequestTracker`, the shutdown-drain mechanism this ADR's admission
gate is explicitly distinct from), ADR 0032 (the `*LimitsProperties`/
`Resolved*.of(...)` pattern `ConcurrencyLimitsProperties` follows), ADR 0034
(the `SqlComplexityValidator` this ADR extends with both SQL-policy fixes).
