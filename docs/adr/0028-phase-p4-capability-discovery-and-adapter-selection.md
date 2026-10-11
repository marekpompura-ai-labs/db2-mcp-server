# ADR 0028: Phase P4 — capability discovery and adapter selection

## Status
Accepted — implemented and verified in this environment (`mvn verify`
green, 174 tests, up from 164; live-verified against the real packaged
jar in three separate scenarios, not assumed from the code alone).

## Date
Proposed 2026-08-17, Accepted 2026-08-17.

## Context
Phase P4 of the adopted database-portability extension (ADR 0021), in
the extension's own words (`docs/master-brief-extension-database-
portability.md` §15):

> Phase P4 — Capability discovery and adapter selection: implement
> versioned `getCapabilities`; expose effective profile and safe limits;
> make Db2 adapter activation explicit; fail fast on invalid adapter
> configuration.

Before this phase, an MCP client had no way to discover this server's
effective capabilities or resource limits except by probing `tools/list`
and inferring meaning from tool presence/absence - workable, but not
what the extension's own §5.2 calls for: a single, versioned, explicit
manifest. Separately, "Db2 adapter activation" was implicit - the server
always used Db2 because only one adapter implementation existed, with
no configuration property making that an explicit, checkable decision,
and no mechanism that would fail loudly if a future operator
misconfigured which product the server should use.

## Decision drivers
- The manifest must report *effective*, not aspirational, capabilities
  (extension §5.2: `readOnlyQuery` must be `false` under `METADATA_ONLY`)
  - it has to read the same `CapabilityProperties`/`QueryLimitsProperties`
  every other policy decision already reads, not maintain an independent
  second copy that could drift.
- `productVersion` must never be a hardcoded string this environment has
  not verified - ADR 0009's Tier 1/Tier 2 honesty framework applies here
  exactly as everywhere else in this project. Querying it live from the
  JDBC driver is both more honest and, conveniently, itself Tier-1
  testable against H2.
- "Fail fast on invalid adapter configuration" should reuse a trusted
  framework mechanism, not hand-written validation - ADR 0015's own
  decision driver, applied again here.
- A change this size, done carefully, tends to surface real defects in
  adjacent code it touches. Found one: `AuditEvent.allowed`'s
  primitive-typed parameters threw a `NullPointerException` whenever a
  caller had no row count to report - a real, previously undiscovered
  bug in already-shipped code (ADR 0025), not a hypothetical one, fixed
  as part of this phase rather than filed for later.

## Considered options
1. **A `CapabilityManifest` domain model (`domain.model`, not a new
   `domain.capability` package) returned by a new `CapabilityPort`,
   implemented by `Db2CapabilityAdapter`, exposed via a new,
   unconditionally-registered `CapabilityTools.getCapabilities()`.**
   Chosen. The extension's own recommended package shape (§4) lists
   `domain.capability` as a sibling of `domain.model` - deliberately not
   followed here, because this project already has an unrelated
   `policy.capability` package (`CapabilityProfile`, the security
   profile) with a completely different meaning ("which security mode is
   active" vs. "what does the manifest say the server can do"). A
   `domain.capability` package sitting next to `policy.capability` would
   be a standing invitation to confuse the two. `domain.model` already
   holds every other MCP-output-shape record; the capability types are
   architecturally identical to those, just newer.
2. **Query `productVersion` live via `Connection.getMetaData().
   getDatabaseProductVersion()` on every call, gracefully degrading to a
   safe placeholder string on failure rather than throwing.** Chosen
   over a hardcoded version string (rejected - unverified in this
   environment, exactly the claim ADR 0009 exists to prevent) and over
   letting a connection failure propagate as a thrown error (rejected -
   `getCapabilities` is meant to work as a discovery tool even when the
   database is temporarily unreachable, the same "a diagnostic feature
   must not be the reason a caller can't proceed" reasoning ADR 0015's
   `EffectiveConfigReporter` already established).
3. **`DatabaseSelectionProperties` (`db2mcp.database.product`, default
   `DB2`) in `adapter.db2`, not `configuration`.** The first draft put it
   in `configuration` (reasoning: "adapter selection is a composition-
   root concern"), but every existing `@ConfigurationProperties` record
   this project has lives inside the layer that primarily consumes it
   (`AuditProperties` in `adapter.observability`, `QueryLimitsProperties`
   in `policy.sql`) with `configuration.EffectiveConfigReporter` reading
   *into* those layers, never the reverse. Moving it to `adapter.db2`
   keeps that established direction intact rather than introducing a new
   `adapter → configuration` dependency this project has never needed
   before.
4. **Rely on `DatabaseProduct`'s single-value closed enum as the entire
   fail-fast mechanism**, rather than a hand-written startup validator.
   Chosen - identical reasoning to ADR 0015's rejection of hand-written
   config validation in favor of Bean Validation/Spring's binder: any
   value other than `DB2` simply cannot bind, and Spring Boot's own
   conversion-failure message already names the exact property, value,
   origin, and reason. Verified live against the real packaged jar
   (Consequences), not assumed from the annotation alone.
5. **Fix the `AuditEvent.allowed` unboxing bug in place, with two new
   regression tests** (`AuditEventTest` at the factory itself;
   `Db2MetadataAdapterSingleResultAuditTest` at the real call site, using
   a hand-built synthetic `SYSCAT.PROCEDURES`/`SYSCAT.PROCPARMS` H2
   fixture, explicitly documented as proving only the instrumentation
   wiring, not DB2 catalog behavior). Both tests were run against the
   *original*, unfixed signature first and confirmed to genuinely fail -
   including a real reproduction of the exact `NullPointerException` at
   the exact call site - before the fix was applied and both were
   confirmed green.

## Decision
`domain.model` gained: `DatabaseProduct` (enum, `DB2` only - "future
values must not be interpreted as implemented support without an
adapter and integration evidence," extension §5.1), `IdentifierRules`
(DB2 LUW: unquoted identifiers folded to uppercase, quoted identifiers
preserve case), `DatabaseDescriptor`, `EffectiveCapabilities`,
`EffectiveLimits`, and `CapabilityManifest` (`contractVersion` "1.0" -
this manifest shape's first version, extension §13's versioning rules
apply to it going forward the same as every other tool contract).

`application.port.out` gained `CapabilityPort`. `adapter.db2` gained
`Db2CapabilityAdapter` (implements it - queries `productVersion` live,
computes `EffectiveCapabilities` from the same `CapabilityProperties`
every policy decision reads, reports `EffectiveLimits` from the same
`QueryLimitsProperties` `executeQuery` enforces) and
`DatabaseSelectionProperties` (`db2mcp.database.product`, default
`DB2`). `mcp.tool` gained `CapabilityTools`, registered unconditionally
in `McpConfig` alongside `SchemaTools` - visible under every capability
profile, so a client under `METADATA_ONLY` can discover `readOnlyQuery:
false` directly rather than inferring it from `executeQuery`'s absence.
`EffectiveConfigReporter` now reports `databaseProduct` alongside its
other four `db2mcp.*` properties records.

Separately: `AuditEvent.allowed`'s `rowCount`/`truncated` parameters
widened from primitive `int`/`boolean` to boxed `Integer`/`Boolean`,
matching the record's own field types - the real, previously-shipped
bug this phase's work surfaced.

## Consequences
- **A real bug found and fixed, not shipped undiscovered.** Both
  `describeProcedure` and `getProcedureSource`'s successful outcome
  threw an unhandled `NullPointerException` instead of returning a
  result, because `Db2MetadataAdapter.instrumented`'s `Integer rowCount`
  (correctly `null` for a single-object result) was passed into an
  `AuditEvent.allowed` overload that unboxed it to a primitive `int`.
  No test caught this before now because both methods are Tier 2 (ADR
  0009 - no `SYSCAT.PROCEDURES` equivalent in H2), so the ALLOWED branch
  was simply never reached by any existing test. Reproduced directly
  (`Integer rowCount = null; AuditEvent.allowed(..., rowCount, ...)` →
  genuine `NullPointerException`), confirmed the two new regression
  tests fail against the original signature and pass against the fixed
  one, then fixed. This is exactly the class of gap Tier 2's "compiled
  and unit-tested with mocks, not exercised against a live instance"
  caveat warns about - a hand-built synthetic catalog fixture, honestly
  labeled as testing only this class's own wiring rather than DB2
  behavior, was what actually caught it.
- **The fail-fast mechanism verified three ways against the real
  packaged jar**, not assumed from the annotations: (1) default
  configuration - `getCapabilities` returns a real MCP `tools/call`
  response with `"isError":false` and `productVersion":"(unavailable -
  could not connect)"` (this environment has no live DB2, exactly the
  honest, non-crashing outcome the design intends); (2)
  `DB2MCP_DATABASE_PRODUCT=ORACLE` - process exits 1, stdout stays 0
  bytes, stderr shows `Property: db2mcp.database.product / Value:
  "ORACLE" / Origin: System Environment Property
  "DB2MCP_DATABASE_PRODUCT" / Reason: ... No enum constant ...ORACLE`;
  (3) the smoke test's own `tools/list` and `Registered tools:` log-line
  assertions, updated from 9 to 10 tools and re-verified green.
- **Both golden MCP contract snapshots updated, diffs reviewed before
  accepting.** The input snapshot (ADR 0013) gained exactly one entry -
  `getCapabilities`, empty input schema, matching a no-argument tool -
  and changed nothing else. The output snapshot (ADR 0025) gained
  exactly one entry - a representative `CapabilityManifest` matching the
  extension's own §5.2 illustrative JSON shape closely. Both diffs were
  inspected before being committed, not blindly regenerated and trusted.
- **`scripts/smoke-test.sh` updated for the same reason the golden
  snapshots were** - its hardcoded tool-count and log-line assertions
  (`9`, `Registered tools: 9`) were correct before this phase and stale
  after it; a real, intentional contract change should make an outdated
  assertion fail loudly, and it did, before being brought up to date.
- **Full suite: 174 tests** (up from 164) - 5 for the `AuditEvent` bug
  fix, 5 for `Db2CapabilityAdapterIntegrationTest` (live H2 version
  query, effective limits, `METADATA_ONLY` vs. `SAFE_QUERY` capability
  flags, graceful degradation on a genuinely broken `DataSource`). JaCoCo
  policy-package gate and all seven ArchUnit rules unaffected.

## Verification / enforcement
`mvn verify -DskipITs` (174 tests). Packaged jar verified three separate
ways (above): normal `getCapabilities` call, invalid-product startup
failure, and the updated smoke test. Both regression tests for the
`AuditEvent` bug confirmed to genuinely fail against the pre-fix code
before being confirmed to pass against the fix.

## Related decisions
ADR 0021 (adopted the extension this phase implements), ADR 0027 (Phase
P3 - the outbound-port pattern `CapabilityPort` extends), ADR 0025 (the
observability pattern `Db2CapabilityAdapter` follows, and the `AuditEvent`
factory this phase found a real bug in), ADR 0015 (config validation and
effective-config report - both the fail-fast mechanism and the "a
diagnostic feature must not crash" reasoning this phase reuses), ADR
0009 (Tier 1/Tier 2 - `productVersion`'s live-query design, and why the
`AuditEvent` bug went uncaught until now), ADR 0006 (capability profiles
- `getCapabilities`'s own unconditional registration mirrors `SchemaTools`
exactly), ADR 0013 (MCP contract snapshot, extended by one entry).
