# ADR 0042: Disconnect-triggered query cancellation (#46)

## Status
Accepted

## Date
Proposed 2026-09-07, Accepted 2026-09-07.

## Context
P2-2 of the 2026-09-01 master-brief gap review (`docs/reviews/2026-09-01-master-brief-gap-review.md`,
tracked as issue #46) confirmed cancellation was not a complete feature:
zero `.cancel(` calls existed anywhere in `src/main/java`; the only
cancellation-adjacent mechanism was `setQueryTimeout` (`Db2QueryAdapter`,
`Db2MetadataAdapter`), relying entirely on driver-enforced timeout, not an
explicit server-initiated `Statement.cancel()`. No dedicated cancellation
metric or audit-decision value existed either.

The issue's own text framed this as a genuine choice, not a foregone
conclusion: "decide whether explicit cancellation ... is worth the added
complexity, or whether driver timeout is an accepted-risk trade-off for
this project's phase." The human project owner chose to implement it,
scoped specifically as **disconnect-triggered cancellation** - when the
MCP client disconnects (stdin EOF) while a query is still running, cancel
it proactively - rather than a general-purpose "cancel this specific
in-flight call" API, which the MCP protocol/this server's transport layer
has no mechanism to request in the first place.

## Decision drivers
- ADR 0031's own fix must not be undermined. `InFlightRequestTracker.awaitDrain`
  already waits out a `shutdownGracePeriod` for an in-flight request's
  response before shutdown proceeds - a real MCP client that sent a
  request and is now closing stdin as part of normal termination still
  expects that last response, and ADR 0031 exists specifically because
  killing that response early is a real, previously-shipped bug. Cancelling
  a running query the instant EOF is observed would reintroduce exactly
  that failure mode for a query that would have finished, and had its
  response delivered, within the grace period. Cancellation is therefore
  gated on the *same* `awaitDrain` timeout already firing the existing
  warning log - only once ADR 0031's own patience has already been
  exhausted, never earlier.
- No request-id-to-statement correlation is needed, and adding one would be
  real complexity spent on a distinction this feature never uses.
  `Db2QueryAdapter` never learns the JSON-RPC request id a tool call is
  answering - that context lives entirely in the transport layer (ADR
  0001's own separation) - and at the moment cancellation actually fires,
  the answer to "which query should be cancelled" is simply "whatever is
  still running," not "this one specific query." `ActiveStatementRegistry`
  is keyed by the `Statement` instance itself for exactly this reason.
- SQLSTATE alone cannot distinguish an explicit cancel from a driver-side
  statement timeout - both produce SQLSTATE `57014` on H2 (and DB2) by
  observation, confirmed directly against the existing
  `executeQuery_timesOutOnExpensiveCrossJoin` test's own evidence. A
  registry-tracked boolean flag (`ActiveStatementRegistry.wasCancelled`),
  checked at the moment the resulting `SQLException` is caught - before the
  statement is deregistered - is what actually tells the two apart for the
  audit trail, not the SQLSTATE.
- `ErrorCategory.QUERY_TIMEOUT` already exists and already fires for
  SQLSTATE `57014` via `Db2SqlStateClassifier` - no new client-visible
  error category was needed. The client-facing distinction barely matters
  anyway: by the time a query is cancelled under this feature, the client
  has already disconnected and will never receive the response. The
  `PolicyDecisionReason.CANCELLED` audit/metrics distinction is what
  actually has an audience - an operator, not the (already-gone) client.

## Decision

### `ActiveStatementRegistry` (`configuration` package)
New component tracking every currently-executing JDBC `Statement`:
`register`/`deregister`/`wasCancelled`/`activeCount`/`cancelAll()`. Backed
by two `ConcurrentHashMap.newKeySet()` sets (active, cancelled), safe for
concurrent registration from tool-execution threads and cancellation from
the shutdown thread. `cancelAll()` tolerates one statement failing to
cancel (e.g. its connection already gone) without stopping the rest -
logged, not propagated.

Placed in `configuration`, alongside `InFlightRequestTracker` (the same
kind of cross-cutting, shutdown-adjacent infrastructure), not
`adapter.observability` - `StdioLifecycleConfig` (also `configuration`)
is this registry's primary caller of `cancelAll()`, and `Db2QueryAdapter`
(`adapter.db2`) depends on it the same direction `Db2QueryAdapter` already
depends on other `configuration`-adjacent infrastructure. Confirmed
against `LayeringArchitectureTest`: no existing rule constrains the
`adapter`/`configuration` dependency direction either way.

### `Db2QueryAdapter.executeQuery` - `StatementCallback`, not `ResultSetExtractor`
The DB2-execution block switched from `jdbcTemplate.query(sql,
ResultSetExtractor)` to `jdbcTemplate.execute((StatementCallback<QueryResult>)
stmt -> ...)` - only a `StatementCallback` hands back the live `Statement`
itself, which is what needs registering. Inside the callback: register,
`stmt.executeQuery(sql)`, process the `ResultSet` exactly as before (byte
accounting, row cap, truncation - all unchanged), deregister in a
`finally`. A caught `SQLException` checks `activeStatementRegistry.wasCancelled(stmt)`
*before* deregistering (deregister clears the cancelled flag too) and, if
true, throws a private `QueryCancelledException` (a plain `RuntimeException`,
not a `DataAccessException` - `JdbcTemplate.execute(StatementCallback)`
only wraps checked `SQLException`s the callback throws, so an unchecked
exception propagates through it unchanged, straight to a dedicated catch
clause, without a second SQLSTATE-based guess). Uncancelled `SQLException`s
rethrow unchanged, still wrapped into `DataAccessException` by
`JdbcTemplate` exactly as before this change.

### `PolicyDecisionReason.CANCELLED` and `AuditEvent.cancelled(...)`
New enum value, new `AuditEvent` factory method mirroring `failed(...)`'s
shape. `Db2QueryAdapter.recordOutcome`'s reason-to-outcome mapping
(previously a nested ternary chain) rewritten as a `switch` expression -
matching `Db2SqlStateClassifier`'s own established style - now that a
third real branch made the ternary chain harder to read than a switch.
Metrics: `outcome="cancelled"` (distinct from `"failed"`/`"rejected"`),
`recordPolicyDecision` fires as usual, `recordRejected` does not (a
cancellation is neither a pre-execution policy rejection nor a successful
result).

### `StdioLifecycleConfig` - cancellation wired into the existing drain-timeout branch
`awaitDrainThenShutdown` gains an `ActiveStatementRegistry` parameter.
When `tracker.awaitDrain(shutdownGracePeriod)` times out (the same branch
that already logged a warning), it now also calls
`activeStatementRegistry.cancelAll()` and logs how many statements were
cancelled. No change to the branch that fires when drain succeeds within
the grace period - a query that finishes in time is entirely unaffected
by this feature.

## Explicitly out of scope
- **A general-purpose "cancel this specific in-flight call" API.** The MCP
  protocol/this server's transport has no mechanism for a client to
  request cancellation of one specific outstanding call, and
  `Db2QueryAdapter` never learns the JSON-RPC request id it's answering
  (ADR 0001's transport/domain separation) - `ActiveStatementRegistry`'s
  "cancel whatever is running" scope is exactly what disconnect-triggered
  cancellation needs, not a narrower gap left unclosed.
- **`Db2MetadataAdapter`/`lookupRefData`.** Only `Db2QueryAdapter.executeQuery`
  gained cancellation support - the pure metadata methods and
  `lookupRefData` are bounded, fast, catalog-only queries in practice; the
  review's own framing centered on `executeQuery`'s arbitrary,
  potentially-long-running SQL. Extending `ActiveStatementRegistry`
  registration to those adapters later would be a small, additive follow-up
  if a real need surfaces, not a gap being silently left open now.
- **`explainQuery`.** Same reasoning - `captureAndReadPlan`'s `EXPLAIN
  PLAN`/read-back queries are short, catalog-table operations (ADR 0033),
  not the kind of long-running arbitrary SQL this feature targets.

## Consequences
- A client that disconnects mid-query no longer leaves that query running
  server-side indefinitely (or until it happens to hit its own, possibly
  much longer, statement timeout) - it's cancelled within, at most,
  `shutdownGracePeriod` (default 10s) of the disconnect being detected.
- Real DB2 resources (locks, cursors, CPU) are freed sooner on client
  disconnect - a real operational improvement, not just an audit-trail
  nicety, though the audit-trail distinction (`CANCELLED` vs
  `EXECUTION_FAILED`) is what's directly test-covered.
- `Db2QueryAdapter`'s constructor gained a required `ActiveStatementRegistry`
  parameter - every direct-construction test site (five files) updated
  accordingly; Spring's own autowiring needed no change (`ActiveStatementRegistry`
  is a `@Component`, resolved automatically).
- `recordOutcome`'s reason-to-outcome mapping is a `switch` expression now,
  not a nested ternary chain - a readability improvement that fell out of
  adding a third real branch, not a separately-motivated refactor.
- No change to any tool's MCP-facing contract - `McpContractSnapshotTest`/
  `McpOutputContractSnapshotTest` both pass with no diff (cancellation is
  an internal shutdown-path behavior; the client that would have received
  a `QUERY_TIMEOUT`-categorized error is, by definition, already gone).

## Verification / enforcement
`ActiveStatementRegistryTest` (`configuration` package): register/deregister/
cancelAll/wasCancelled/activeCount behavior against mocked `Statement`
objects, including a statement that fails to cancel (tolerated, logged,
does not stop the rest) and `cancelAll()` called twice against a
still-registered statement.

`Db2QueryAdapterIntegrationTest.executeQuery_cancelledMidFlightThrowsQueryTimeoutAndRecordsCancelledInAudit`:
a real, mid-flight `activeStatementRegistry.cancelAll()` call against a
real H2 connection - not a mocked `Statement` - running the same
expensive-cross-join SQL `executeQuery_timesOutOnExpensiveCrossJoin`
already uses, but with a deliberately long (120s) configured timeout so a
passing result can only mean cancellation actually worked, not that the
JDBC timeout fired first for an unrelated reason. Confirmed live: the
query was cancelled in ~131ms (SQLSTATE `57014`), the thrown
`McpToolException` carries `ErrorCategory.QUERY_TIMEOUT`, and the audit
log records `decision=CANCELLED` - the exact end-to-end path this project
actually ships, not just each piece in isolation.

`./mvnw verify -DskipITs` - full suite green, no regression to any
existing test (all five `Db2QueryAdapter` direct-construction test sites
updated for the new constructor parameter). ArchUnit re-run unchanged (no
new layering violation - confirmed `adapter`/`configuration`'s dependency
direction is unconstrained by any existing rule before relying on it).

## Related decisions
ADR 0001 (stdio-only transport, the domain/transport separation
`ActiveStatementRegistry`'s id-free design relies on), ADR 0007 (the
`setQueryTimeout` mechanism this feature complements, not replaces), ADR
0016 (graceful shutdown on client disconnect - the shutdown sequence this
feature's cancellation call is added to), ADR 0018 (`McpToolException`/
`ErrorCategory` - `QUERY_TIMEOUT` reused, not extended), ADR 0031 (the
`InFlightRequestTracker.awaitDrain` timeout this feature's cancellation is
deliberately gated on, not triggered earlier than).
