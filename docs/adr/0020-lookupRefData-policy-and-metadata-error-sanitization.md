# ADR 0020: Close two P0 findings from external code review — lookupRefData policy bypass and unsanitized metadata errors

## Status
Accepted — implemented and verified in this environment (`mvn verify`
green, 140 tests, up from 133; the packaged jar's real Spring context
confirmed live, both profiles).

## Date
Proposed 2026-08-14, Accepted 2026-08-14.

## Context
An external code review of a code-only snapshot of this repository
(`db2-mcp-server-review.zip`, a `git archive` export at commit `d064010`)
reported two release-blocking findings, independently verified against
the actual code before being accepted, not taken on the reviewer's word:

1. **CR-001 — `lookupRefData` bypassed the intended data-access policy.**
   It was registered in `SchemaTools` alongside genuine read-only metadata
   tools, so `McpConfig` exposed it unconditionally regardless of
   capability profile — confirmed directly: `schemaToolCallbackProvider`
   added `schemaTools` (which then contained `lookupRefData`) with no
   guard, while `queryTools` (`executeQuery`) was already correctly
   gated behind `allowsQueryExecution()`. Unlike `executeQuery`,
   `lookupRefData` returns actual row values, not metadata, from
   caller-supplied schema/table/column identifiers, and its only
   enforcement was `requireIdentifier`'s character-syntax regex — no
   schema allowlist, no row cap, no timeout, no audit/metrics/trace. An
   MCP client under `METADATA_ONLY` could use it to extract two columns
   at a time from any table the configured database principal could
   read.
2. **CR-002 — Metadata tools had no shared sanitized error boundary.**
   `Db2QueryAdapter` already sanitizes `DataAccessException` into
   `McpToolException` (ADR 0018). `Db2MetadataAdapter` did not — confirmed
   by grepping the file for `catch`, which matched nothing outside the
   static row-mapper methods' `throws SQLException` declarations. A real
   driver exception from any of the seven catalog-backed tools (or
   `lookupRefData`) would reach the MCP client with whatever message the
   JDBC driver produced, contradicting the threat model's untrusted-client
   assumption and the README's sanitized-error claim.

Both were spot-verified against the running code before any fix was
written — not just accepted from the review report — the same "verify,
don't assume" discipline this project has applied to every other claim
in it (decompiling dependencies for ADR 0018, testing scripts against
deliberately broken environments for the smoke test, etc.).

## Decision drivers
- Both findings are genuine, currently-exploitable gaps, not
  hypothetical — CR-001 in particular directly contradicts
  `METADATA_ONLY`'s entire purpose (ADR 0006: "a tool a profile doesn't
  allow is invisible to the client, not merely rejected when called").
- Fix must reuse the exact machinery `executeQuery` already has
  (capability gate, schema allowlist, row/timeout limits, sanitized
  errors, audit/metrics/spans) rather than inventing a parallel,
  differently-shaped mechanism for one tool.
- Must not weaken or change behavior for the seven genuinely read-only
  metadata tools beyond adding error sanitization — they are not the
  vulnerability.
- Full architectural error-boundary unification (one shared invocation
  pipeline for every MCP tool, as CR-002's remediation section describes
  in its ideal form) is out of scope for this pass — it's Phase P3 of the
  now-adopted database-portability extension (see "Related decisions").
  This ADR closes the acceptance evidence CR-002 actually requires (no
  raw driver detail reaches the client) with the smallest change that
  does it, not the full refactor.

## Considered options
1. **Reuse `Db2QueryAdapter`'s exact pattern for `lookupRefData`; add a
   `sanitizeErrors` wrapper to every `Db2MetadataAdapter` method.**
   Chosen. Minimal diff, proven machinery, no new abstractions invented.
2. **Move `lookupRefData` behind a configured reference-data dataset list**
   (CR-001's alternative remediation: `db2mcp.reference-data.datasets`,
   naming specific schema/table/column combinations instead of trusting
   caller-supplied identifiers). Rejected for this pass, not permanently:
   a real improvement, but a larger, contract-changing scope decision
   (what does an LLM client see when it can no longer pass its own table
   name?) than fixing the immediate policy bypass. Left as a candidate
   for the CR-005-style payload/value-normalization pass that's still
   open.
3. **Build the full common MCP invocation boundary now** (CR-002's ideal
   remediation, and the database-portability extension's Phase P3).
   Rejected for this pass: correct destination, wrong order — the
   extension's own working method says close P0 findings before
   portability refactoring, and a boundary big enough to unify nine
   tools' policy/audit/error handling is not a "smallest coherent
   refactor" for a same-day security fix.

## Decision
`lookupRefData` moved out of `SchemaTools` into a new `ReferenceDataTools`
class, conditionally registered in `McpConfig` exactly like `QueryTools`
(`allowsQueryExecution()`). `Db2MetadataAdapter.lookupRefData` gained the
same four-stage guard chain as `Db2QueryAdapter.executeQuery`
(capability → identifier validation → `SchemaAllowlistValidator
.validateSchema` → bounded execution via a dedicated `JdbcTemplate` with
`maxRows + 1` truncation and a query timeout), plus audit/metrics/span
recording under a synthetic `REFDATA[SCHEMA.TABLE]` fingerprint (no raw
SQL exists to fingerprint, since there's no SQL text on the wire).
`SchemaAllowlistValidator` gained a `validateSchema(String)` overload for
this — the same allowlist as `validate(String sql)`, without needing SQL
text to parse. All eight `Db2MetadataAdapter` methods now route
`DataAccessException` through a shared `sanitizeErrors` helper into
`McpToolException` via the existing `Db2SqlStateClassifier`. The
SQLSTATE-extraction helper (previously private inside `Db2QueryAdapter`)
moved to a small package-private `Db2Exceptions` class shared by both
adapters — deliberately kept out of `domain.error` (framework-free by
ArchUnit rule and the portability extension's own layering requirement),
since `DataAccessException` is a Spring type.

## Consequences
- **Verified at three independent levels, not just code-reviewed:**
  1. Unit/integration: `McpConfigTest` proves `METADATA_ONLY` registers
     exactly 7 tools (`lookupRefData`/`executeQuery` absent) and
     `SAFE_QUERY`/`ADVANCED_QUERY` register 9;
     `Db2MetadataAdapterLookupRefDataIntegrationTest` proves the
     adapter-level defense-in-depth check independently rejects
     `METADATA_ONLY` (`CAPABILITY_DISABLED`) and a disallowed schema
     (`SCOPE_DENIED`) even if registration were ever bypassed;
     `Db2MetadataAdapterExceptionSanitizationTest` proves a real H2
     `DataAccessException` (no `SYSCAT.SCHEMATA` table in the ITSM H2
     fixture) becomes `McpToolException` with no driver/catalog detail
     in the message.
  2. Real packaged jar: `java -jar` with `DB2MCP_CAPABILITY_PROFILE=
     METADATA_ONLY` logs `Registered tools: 7`; `SAFE_QUERY` logs
     `Registered tools: 9` — the real Spring context wiring, not a
     hand-built test double.
  3. Full suite: **140 tests** (up from 133), all green under `mvn
     verify -DskipITs`, including the pre-existing JaCoCo policy-package
     coverage gate (ADR 0014) and all 5 ArchUnit layering rules (ADR
     0005) — confirming the new `Db2Exceptions` class and
     `ReferenceDataTools` extraction introduced no layering violation.
- **`lookupRefData`'s row cap is silent, not surfaced as metadata.**
  Entries beyond `maxRows` are dropped the same way `executeQuery`'s were
  before `QueryResult.truncated` existed, but `ReferenceDataEntry`'s
  return type has no equivalent truncation field. Left open deliberately
  rather than expanded in scope here — matches CR-005's still-open
  payload/value-normalization gap, not a new one this ADR introduces.
- **CR-002's ideal remediation (one common MCP invocation boundary) is
  not yet built.** This ADR satisfies the acceptance evidence (no raw
  SQL, driver class, hostname, or stack trace crosses the boundary for
  any tool) with per-adapter sanitization, not a unified pipeline. That
  unification is Phase P3 of the database-portability extension (ADR
  0021).
- **Existing tests updated, not weakened to compile**: both
  `Db2MetadataAdapter` call sites in `Db2DemoScenarioIntegrationTest`
  updated to the new constructor with real `CapabilityProperties`/
  `SchemaAllowlistValidator`/`QueryLimitsProperties`, same pattern
  `Db2QueryAdapter`'s test helper already used.

## Verification / enforcement
`mvn verify -DskipITs` (140 tests, JaCoCo gate, ArchUnit). Packaged jar
run twice with `DB2MCP_CAPABILITY_PROFILE=METADATA_ONLY` and `SAFE_QUERY`
respectively, confirming `Registered tools: 7` and `Registered tools: 9`
in real startup logs.

## Related decisions
ADR 0006 (capability profiles — the "invisible, not rejected" contract
this fix extends to `lookupRefData`), ADR 0008 (schema allowlist —
`validateSchema` reuses its configuration), ADR 0018 (structured error
model — `McpToolException`/`ErrorCategory`/`Db2SqlStateClassifier`, all
reused rather than duplicated), ADR 0021 (adoption of the
database-portability extension this review's companion document
proposed — CR-002's full remediation belongs to that extension's Phase
P3, not this ADR).
