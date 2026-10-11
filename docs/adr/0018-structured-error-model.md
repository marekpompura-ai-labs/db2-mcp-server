# ADR 0018: A structured error model for MCP tool failures

## Status
Accepted — implemented and verified in this environment (`mvn verify`
green, 128 tests; the actual JSON-RPC error response observed over a
real MCP protocol session, not just asserted in a unit test).

## Date
Proposed 2026-08-13, Accepted 2026-08-13.

## Context
Master brief §11.4 asks for stable error categories (`VALIDATION_FAILED`,
`CAPABILITY_DISABLED`, `POLICY_REJECTED`, `SCOPE_DENIED`, `QUERY_TIMEOUT`,
`RESULT_LIMIT_EXCEEDED`, `DB2_AUTHORIZATION_FAILED`,
`DB2_OBJECT_NOT_FOUND`, `DB2_EXECUTION_FAILED`, `INTERNAL_ERROR`),
SQLSTATE/SQLCODE exposed only when safe, and stack traces never exposed
by default. This was explicitly deferred at every point it came up
during Phases 3–5 — `Db2QueryAdapter` sanitized errors down to a generic
message plus SQLSTATE (ADR 0007's adversarial-catalogue pass) but never
built the category system itself, and `PolicyDecisionReason`'s own
Javadoc named this gap directly: "Deliberately not the same thing as a
public structured error model (master brief section 11.4), which is
Phase 5 scope." Phase 5 came and went without it being built — a real
gap, not a hidden one, since it was named in code comments the whole
time.

## Decision drivers
- Must actually reach the MCP client as a stable, parseable shape — not
  just exist as an internal Java exception hierarchy nobody outside the
  JVM can see.
- Must not guess how Spring AI's MCP server surfaces an exception to the
  client — verify it directly, the same discipline this project has
  applied to JSqlParser (ADR 0007), logback (ADR 0010), and Spring Boot's
  shutdown hooks (ADR 0016).
- Must not duplicate or replace `PolicyDecisionReason` (ADR 0010) — that
  enum is audit-log-internal by design and stays that way; this is a
  parallel, client-facing vocabulary.
- SQLSTATE classification into the DB2-specific categories
  (`DB2_AUTHORIZATION_FAILED`, `DB2_OBJECT_NOT_FOUND`) must be honest
  about what's verified here versus asserted from documentation, given
  no live DB2 instance exists in this environment (ADR 0009).

## Considered options
1. **A `McpToolException` carrying a stable `ErrorCategory` enum, whose
   `getMessage()` is a small rendered JSON object.** Chosen. Verified
   first, not assumed: decompiled this project's exact Spring AI MCP
   dependency (`spring-ai-model:1.0.0`, `mcp:0.10.0`) to find out
   precisely how a thrown exception reaches the client.
   `MethodToolCallback.callMethod` wraps any `InvocationTargetException`
   in a `ToolExecutionException` whose message is the cause's message
   verbatim; `McpToolUtils.toSyncToolSpecification`'s handler catches
   `Exception`, and on catch builds `CallToolResult([TextContent(
   exception.getMessage())], isError: true)`. There is no separate
   channel for a structured code — the exception's `getMessage()` *is*
   the entire error payload a client receives. That fact drove the whole
   design: the category has to live inside that one string, so it's
   rendered as JSON at construction time.
2. **A discriminated-union return type** (e.g. `QueryResult` becomes a
   sealed `Success`/`Error` variant returned normally, never thrown).
   Rejected: would change every tool's return type and break the MCP
   contract snapshot (ADR 0013) for no benefit over option 1, and
   fighting the framework's actual exception-based error path (confirmed
   by decompilation) rather than working with it.
3. **Leave `getMessage()` as free text, prefixed with the category name**
   (e.g. `"[SCOPE_DENIED] Table reference ..."`.) Rejected: not
   machine-parseable without a bespoke parser on the client side; JSON
   costs nothing extra to produce and every MCP client already parses
   JSON as a matter of course.

## Decision
Add `domain.error.ErrorCategory` (the ten brief-mandated categories) and
`domain.error.McpToolException`, whose constructor renders `{errorCode,
message, sqlState}` as JSON via Jackson and passes it straight to
`RuntimeException`'s message. Add `domain.error.Db2SqlStateClassifier`,
mapping SQLSTATE to category: `57014` → `QUERY_TIMEOUT`, `42501` →
`DB2_AUTHORIZATION_FAILED`, `42704` → `DB2_OBJECT_NOT_FOUND`, anything
else → `DB2_EXECUTION_FAILED`. `Db2QueryAdapter.executeQuery` now throws
`McpToolException` at every guard-chain rejection point
(`CAPABILITY_DISABLED`, `VALIDATION_FAILED`, `SCOPE_DENIED`) and wraps
`DataAccessException` through the classifier.

## Consequences
- **Verified end-to-end over a real MCP JSON-RPC session, not just a
  unit test.** Drove the packaged jar through an actual `initialize` →
  `notifications/initialized` → `tools/call` sequence via a raw
  stdin/stdout pipe (Python subprocess, no MCP client library involved -
  just the protocol itself) and observed the literal response for a
  policy-rejected `executeQuery` call:
  `{"jsonrpc":"2.0","id":2,"result":{"content":[{"type":"text","text":
  "{\"errorCode\":\"SCOPE_DENIED\",\"message\":\"Table reference
  'SYSCAT.TABLES' is not schema-qualified with an allowed schema\",
  \"sqlState\":null}"}],"isError":true}}`. This is what a real MCP
  client actually receives, not an inference from reading the source.
- **`57014`'s classification is empirically grounded, not just
  documented**: it's the exact SQLSTATE
  `Db2QueryAdapterIntegrationTest.executeQuery_timesOutOnExpensiveCrossJoin`
  already observes from a real H2 JDBC statement-timeout. `42501`/`42704`
  are DB2's documented SQLSTATEs for authorization/undefined-object
  failures, asserted from documentation and not verified against a live
  DB2 instance — stated plainly as the same Tier 2 gap ADR 0009 already
  covers, not silently upgraded to "verified."
- **A real, incidental finding from the same test run**: H2 reports
  `42S22` for an unknown-column error, not DB2's real `42703` — an
  unrecognized SQLSTATE, which the classifier correctly falls through to
  `DB2_EXECUTION_FAILED` for rather than misclassifying. The classifier's
  honest default path was exercised for real, not just by construction.
- **`ErrorCategory.POLICY_REJECTED` and `RESULT_LIMIT_EXCEEDED` are
  defined but currently unreachable** from this codebase — stated
  directly in the enum's own Javadoc rather than left for a reader to
  wonder about. The more specific categories cover every actual
  rejection path `Db2QueryAdapter` has, and row-limit truncation is
  modeled as a successful, typed result (`truncated: true`), not an
  error.
- **`PolicyDecisionReason` (ADR 0010) is untouched and stays
  audit-log-internal** — its own Javadoc updated to point at this ADR
  instead of a stale "Phase 5 scope" note, but no behavior changed.
- **Existing tests updated, not just left green by coincidence**: every
  `Db2QueryAdapterIntegrationTest`/`Db2DemoScenarioIntegrationTest`
  assertion that previously checked `IllegalArgumentException`/
  `IllegalStateException` now checks `McpToolException` and, where it
  matters, the specific `ErrorCategory` — not weakened to a looser
  assertion to make the change compile.
- **Full suite: 128 tests** (up from 118), all green under `mvn verify
  -DskipITs`. Two new unit test files
  (`McpToolExceptionTest`, `Db2SqlStateClassifierTest`) cover the new
  domain types in isolation, with no Spring context needed.

## Verification / enforcement
`mvn verify` runs the full suite including the new tests. Independently,
a live MCP protocol session against the packaged jar confirmed the exact
JSON-RPC bytes a real client receives for a rejected `executeQuery` call.

## Related decisions
ADR 0007 (the original minimal error sanitization this ADR completes),
ADR 0009 (Tier 1/Tier 2 verification honesty — applies to the
DB2-specific SQLSTATE classifications), ADR 0010 (`PolicyDecisionReason`
— the parallel, audit-internal vocabulary this ADR's `ErrorCategory`
deliberately does not merge with), ADR 0013 (MCP contract snapshot — this
ADR's design was chosen specifically to avoid needing to touch it).
