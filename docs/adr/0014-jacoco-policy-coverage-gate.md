# ADR 0014: JaCoCo coverage gate on the policy package

## Status
Accepted — implemented and verified in this environment (`mvn verify`
green, 103 tests; the gate proven able to actually fail before being
trusted).

## Date
Proposed 2026-08-13, Accepted 2026-08-13.

## Context
Master brief §10 calls for maintainability gates that keep the project
safe to keep changing. `policy.sql` and `policy.capability`
(`SchemaAllowlistValidator`, `SqlStructuralValidator`, `SqlFingerprint`,
`CapabilityProfile`, and their properties records) are the code that
decides `ALLOWED` vs. `REJECTED` for every `executeQuery` call — this is
exactly the code where an untested branch is most consequential: a
missed edge case here is a missed security check, not a cosmetic bug.
Nothing currently measures or enforces how much of it is actually
exercised by tests.

## Decision drivers
- Must fail the build on a real coverage regression, not just report a
  number nobody looks at.
- Must scope to the policy package specifically — this project
  deliberately isn't chasing a blanket coverage percentage across
  Spring configuration classes, DTOs, and other low-risk code where
  coverage is a weak proxy for correctness (master brief's own caution
  against "tools for badge count").
- Thresholds must reflect what's actually measured in this codebase, not
  a round number picked without evidence — this project's standing
  "verify, don't assume" discipline applies to gate thresholds too.

## Considered options
1. **`jacoco-maven-plugin`, a `check` rule scoped to the policy
   package, bound to `verify`.** Real, industry-standard coverage
   instrumentation (bytecode-level, via a Java agent) — not a hand-rolled
   line counter. Fails the build on regression via `mvn verify`, the same
   phase Maven Failsafe already uses for the (DB2-only) integration
   tests.
2. **Element `BUNDLE` with a class-path `<includes>` filter to scope to
   the policy package.** Tried first, and it's the intuitive-looking
   approach — but a real finding here: for `BUNDLE`-scoped rules,
   `<includes>`/`<excludes>` filter against the *bundle's own name* (the
   Maven module name), not against member classes. Set the filter to
   anything that doesn't match the module name and JaCoCo's `Rule.matches`
   check simply returns false for that rule — no classes are evaluated,
   no limits are checked, and the plugin reports "All coverage checks
   have been met" with **zero classes actually verified**. Confirmed by
   raising the minimum to an impossible 0.99 and watching the build still
   pass; the trivial-pass persisted with several different filter syntax
   attempts. Rejected once understood.
3. **Element `PACKAGE` with `<includes>` matching the policy package by
   dot-separated name.** What option 2 was reaching for. `PACKAGE`-scoped
   rules iterate each package and match the filter against each
   package's own fully-qualified name, so `io.github.marekpompura.db2mcp.policy.*`
   correctly selects `policy.sql` and `policy.capability` and checks each
   against the same limits independently. Confirmed working by the same
   "raise it to 0.99 and watch it actually fail" test that exposed
   option 2's problem — this time with a real, detailed violation message
   naming the package and the exact ratio.
4. **A third-party coverage service (Codecov, Coveralls).** Rejected:
   requires network egress to an external service this environment
   doesn't have, and isn't needed for a local build gate.

## Decision
Add `jacoco-maven-plugin` 0.8.12 with `prepare-agent` (instrumentation)
and `report` bound to `test`, plus a `check` execution bound to `verify`
with one `PACKAGE`-element rule, `includes:
io.github.marekpompura.db2mcp.policy.*`, minimums **LINE 0.95** and
**BRANCH 0.90** — set from what this codebase actually measures after
closing two real gaps found while building this gate (see Consequences),
not picked as a round number in advance.

## Consequences
- **Two real, previously untested defensive paths were found and
  closed**, not just measured: `SqlFingerprint` had *no dedicated test
  file at all* before this — its three "never throws" branches (null
  input, unparseable input, any other parse-time exception) were
  untested. `SchemaAllowlistValidator.validate` was similarly missing
  direct tests for its own parse-failure and empty-statement branches
  (as opposed to `SqlStructuralValidator`'s, which run earlier in the
  real guard chain and were already covered elsewhere). Both got new,
  direct tests (`SqlFingerprintTest.java` — new file — and two new cases
  in `SchemaAllowlistValidatorTest.java`).
- **One branch deliberately left uncovered, stated rather than hidden:**
  `SchemaAllowlistValidator.extractSchema`'s trailing-dot guard (`dot ==
  unquoted.length() - 1`) has no realistic way to be exercised through
  `validate()` — it would require `TablesNamesFinder` to return a table
  reference ending in a bare `.` with nothing after it, which does not
  appear to be producible from any SQL that JSqlParser's grammar accepts
  as valid in the first place. Contriving a fake test around a private
  method's internal branch to force 100% would test the test, not the
  code. The 0.90 branch minimum was set with this specific known gap in
  mind rather than set blind and then explained away.
- **Real methodology finding, the actual point of this ADR beyond the
  gate itself:** a `BUNDLE`-element rule's `<includes>`/`<excludes>`
  filters against the bundle's own name, not its member classes — using
  them to scope to a package silently disables the rule rather than
  erroring. Found by the same "prove the check can fail" discipline as
  ADR 0005 and ADR 0013 (raise the threshold to something impossible and
  confirm a real failure, not just a passing green tick) — which is
  exactly what caught this, since the "impossible" threshold trivially
  passed instead of failing.
- **Measured after closing the two gaps above:** `policy.sql` — LINE
  100% (0 missed), BRANCH 96.15% (25/26, the one known gap above);
  `policy.capability` — LINE 100%, BRANCH 100% (2/2, only
  `CapabilityProfile` has any branches to cover). Thresholds set below
  both at 0.95/0.90, leaving real margin rather than pinning to the
  exact current number.
- **JaCoCo's instrumentation agent cannot instrument JSqlParser's
  generated lexer** (`CCJSqlParserTokenManager.jjMoveNfa_0`) —
  `MethodTooLargeException`, a real ASM bytecode-size limit hit by a
  generated parser method. This is caught internally by the agent and
  does not fail the build or affect the policy-package coverage numbers
  (that class isn't part of this project's own code), but it does print
  a stack trace to the test log on every run — noted here so it isn't
  mistaken for a real problem later.
- **Full suite: 103 tests** (up from 95), all green under both `mvn
  test` and `mvn verify -DskipITs`. Packaged-jar smoke test re-run:
  `Registered tools: 8` under the default `METADATA_ONLY` profile,
  stdout 0 bytes, zero `lsof` network file descriptors — JaCoCo is a
  build-time-only Java agent and doesn't ship in or affect the packaged
  runtime jar.

## Verification / enforcement
`mvn verify` runs `jacoco:check` in the `verify` phase; a coverage
regression in `policy.sql` or `policy.capability` below the stated
minimums fails the build. Proven able to fail, not just assumed: the
`PACKAGE`-scoped rule was confirmed to genuinely enforce by temporarily
raising `LINE` to `0.99`, observing a real `[WARNING] Rule violated for
package ...` message and `BUILD FAILURE`, then restoring the real
0.95/0.90 minimums.

## Related decisions
ADR 0005 (ArchUnit — a different kind of gate, structural rather than
coverage-based; same "prove it can fail" verification discipline), ADR
0013 (MCP contract snapshot tests — same Phase 5 maintainability-gates
effort), ADR 0007 (the `parseStatements`/`CCJSqlParserUtil.parse("")`
finding that `SqlFingerprintTest` and the new
`SchemaAllowlistValidatorTest` cases both independently reconfirm).
