# ADR 0029: Phase P5 — portability evidence

## Status
Accepted — implemented and verified in this environment (`mvn verify`
green, 191 tests, up from 174; zero `src/main` changes - this phase adds
evidence, not implementation).

## Date
Proposed 2026-08-17, Accepted 2026-08-17.

## Context
Phase P5 of the adopted database-portability extension (ADR 0021), in
the extension's own words (`docs/master-brief-extension-database-
portability.md` §15):

> Phase P5 — Portability evidence: implement fake adapter; run common
> adapter/MCP contract tests against Db2 wiring and fake wiring; snapshot
> inputs and representative outputs; update architecture diagrams and
> README wording.

This is the last phase of the extension proper (Phase P4/ADR 0028 closed
capability discovery and adapter selection). Its entire purpose is
evidence, not new capability: §15's own required working method states
"use a fake adapter only to prove dependency inversion and contract
portability," and the extension is explicit elsewhere (§3.3) that
production Oracle/PostgreSQL adapters remain out of scope. Phases P3
(ADR 0027) and P4 (ADR 0028) already did the actual architectural work
- `MetadataPort`/`QueryPort`/`CapabilityPort` return only domain-owned
types, and nothing above `adapter.db2` imports JDBC or Db2 classes. P5's
job is to prove that claim empirically rather than leave it as an
inference from reading the code.

## Decision drivers
- The extension's Definition of Done (§16) is specific: "fake-adapter
  contract tests prove that the MCP/application layers are not Db2-bound."
  That's a runtime claim, not a static one - ArchUnit already proves the
  *absence* of a forbidden dependency (ADR 0005, ADR 0027); it can't
  prove a *presence* of substitutability. Only actually running the real
  `mcp.tool` classes against a non-Db2 backend proves that.
- `DatabaseProduct` (ADR 0028) is a deliberately closed, evidence-gated
  enum - "future values must not be interpreted as implemented support
  without an adapter and integration evidence" (extension §5.1). A fake
  test double is not that evidence, so it must not report itself as one
  of the enum's values or be allowed to expand the option space
  `db2mcp.database.product` accepts in production.
- `MetadataPort`'s seven catalog-backed methods are Tier 2 for the real
  Db2 adapter (ADR 0009) - a "common contract suite run against both
  adapters" claim for those methods would either always fail on the Db2
  side (not a comparison) or require a synthetic catalog fixture that
  proves nothing about real DB2 behavior. Honest scoping matters more
  here than uniform coverage across all eight methods.
- A fake adapter that duplicates production code paths, gates, or error
  semantics stops being a *fake* and starts being a second real
  implementation the extension explicitly says not to build (§3.3, §15
  item 8: "do not add Oracle/PostgreSQL dependencies or placeholder
  production adapters").

## Considered options
1. **`FakeDatabaseAdapter` (test-only, `testsupport.fake`) implementing
   `MetadataPort` and `QueryPort`, deliberately not `CapabilityPort`.**
   Chosen. Considered implementing all three ports for uniformity, but
   `CapabilityPort`'s `CapabilityManifest.database().product()` would
   have forced a choice between two bad options: report
   `DatabaseProduct.DB2` from a non-Db2 test double (misrepresents the
   evidence-gated enum's meaning), or add a `FAKE`/`TEST` enum constant
   (which would then also be a *bindable* value of
   `db2mcp.database.product` in production, weakening ADR 0028's
   fail-fast adapter-selection guarantee - the enum has no way to be
   "valid for tests only"). Not implementing the port avoids both;
   `CapabilityPort` already has its own real, Tier 1, H2-verified
   adapter and doesn't need a fake counterpart to prove the same point
   `MetadataPort`/`QueryPort` already prove.
2. **The fake is unconditionally successful - no capability/schema
   gates, no not-found branch, no SQL parsing.** Chosen over replicating
   Db2 adapter's guard chain or error semantics, which are already
   tested against Db2 itself and aren't what this phase needs to prove.
   The fake's only job is "same result *shape*, any input" - a policy
   gate belongs to the real adapters and the shared `policy` package,
   not to a test double standing in for a different database.
3. **Contract test scope: full dual-adapter comparison for `QueryPort`
   (fully Tier 1) and `MetadataPort.lookupRefData` (the one Tier 1
   method on that port); fake-only coverage for `MetadataPort`'s other
   seven methods, already exercised through the real `mcp.tool` classes
   by a separate end-to-end test.** Chosen over either overclaiming (a
   "dual-adapter" test that always fails on the Db2 side for Tier 2
   methods) or underclaiming (skipping the genuinely available Tier 1
   comparisons). Each test's Javadoc states plainly which claim it does
   and doesn't make - the same honesty standard ADR 0009 established for
   every other Tier 1/Tier 2 claim in this project.
4. **The strongest evidence is running the real, production `mcp.tool`
   classes against the fake, not an abstract "contract suite" alone.**
   `FakeAdapterMcpToolsTest` wires `SchemaTools`/`QueryTools`/
   `ReferenceDataTools` - the actual classes a real MCP client talks to
   - with `FakeDatabaseAdapter` and exercises all nine of their tool
   methods (`getCapabilities` excluded, per option 1). This is more
   direct and more convincing than generic structural assertions alone:
   it's not "an adapter satisfying an interface," it's "the literal
   production inbound path, working end to end, with zero Db2/JDBC
   anywhere in the call graph."

## Decision
`FakeDatabaseAdapter` (`src/test/java/.../testsupport/fake/`) implements
`MetadataPort` and `QueryPort` with a small, fixed in-memory dataset
(`FAKESCHEMA.WIDGET`, `FAKESCHEMA.WIDGET_STATUS_REF`, one procedure).
Confirmed by inspection to import nothing from `java.sql`, `javax.sql`,
or any Db2-specific package.

`FakeAdapterMcpToolsTest` wires the real `SchemaTools`/`QueryTools`/
`ReferenceDataTools` with it and calls all nine backed tool methods,
asserting on the real returned `domain.model` values - the centerpiece
evidence for "MCP/application layers are not Db2-bound."

`QueryPortContractTest` and `MetadataPortContractTest` are genuinely
reusable, parameterized (`@MethodSource`) contract suites: the exact
same structural assertions (non-null result; `rowCount` matches
`rows.size()`; every row's length matches `columns.size()`; reference
data entries have non-blank code/description) run against both a real,
H2-backed `Db2QueryAdapter`/`Db2MetadataAdapter` and
`FakeDatabaseAdapter` - proving the two implementations are
interchangeable by the caller's own criteria, not merely by both
compiling against the same interface.

No `src/main` file changed in this phase - P3 (ADR 0027) and P4 (ADR
0028) had already done all the architectural work; P5 only needed to
add test-side evidence that it held.

## Consequences
- **Zero production code changed, confirmed by `git status` before
  committing, not assumed.** The strongest possible demonstration that
  Phase P3's dependency inversion was done correctly the first time -
  proving portability required adding a test double and some tests, not
  touching a single line of `mcp.tool`, `application`, or `domain`.
- **A real bug caught while writing the parameterized tests, not shipped
  undiscovered.** The first version of `QueryPortContractTest` reused
  one literal H2 database name (`"query_port_contract"`) across all
  three parameterized test methods. Because `ItsmH2Database` uses
  `DB_CLOSE_DELAY=-1` (the in-memory database survives past the first
  connection closing, deliberately, so a single test can hold multiple
  connections), the second and third test methods reconnected to the
  *same* already-populated database left behind by the first, and
  `CREATE SCHEMA ITSM` failed because the schema already existed.
  Reproduced directly, fixed with a per-invocation unique name
  (`AtomicInteger` suffix) - the same "give each test its own database"
  discipline `Db2QueryAdapterIntegrationTest` and others already follow,
  just missed on the first pass here.
- **A second, smaller mistake caught the same way**: `@MethodSource`
  streams of `Named<T>` must be consumed with the test parameter typed
  as `T`, not `Named<T>` - JUnit 5 unwraps `Named` for display purposes
  only. The first draft of both parameterized tests declared the
  parameter as `Named<Supplier<QueryPort>>`, which fails at runtime with
  a parameter-resolution error, not a compile error. Both fixed before
  being trusted.
- **Honest scope boundary documented, not glossed over**: `MetadataPort`
  Tier 2 methods (seven of eight) have no dual-adapter comparison in
  this phase, and `CapabilityPort` has no fake counterpart at all - both
  explicitly justified above rather than silently narrower than the
  extension's own phrasing might suggest.
- **The extension's own "allowed portfolio statement" (§14) is now
  earned, not aspirational** - see the README update accompanying this
  ADR.
- **Full suite: 191 tests** (up from 174) - 9 (`FakeAdapterMcpToolsTest`),
  6 (`QueryPortContractTest`, 3 assertions × 2 adapters), 2
  (`MetadataPortContractTest`, 1 assertion × 2 adapters). All green
  under `mvn verify -DskipITs`. JaCoCo policy-package gate and all seven
  ArchUnit rules unaffected - none of this phase's code lives in a
  package those rules scan (`@AnalyzeClasses` excludes tests), and none
  of it needed to.

## Verification / enforcement
`mvn verify -DskipITs` (191 tests). `git status` confirmed zero
`src/main` changes before committing. Both discovered test bugs
(database-name collision, `Named<T>` parameter type) reproduced and
fixed, not assumed correct on the first write.

## Related decisions
ADR 0021 (adopted the extension this phase closes out), ADR 0027 (Phase
P3 - the dependency inversion this phase provides runtime evidence for),
ADR 0028 (Phase P4 - `DatabaseProduct`'s evidence-gated design, the
reason `CapabilityPort` has no fake implementation), ADR 0009 (Tier
1/Tier 2 honesty - the same framework applied to scoping this phase's
dual-adapter claims), ADR 0005 (ArchUnit layering - the static half of
the dependency-inversion proof; this ADR is the dynamic half).
