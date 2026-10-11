# ADR 0024: Honest CI test tiers (CR-006) and a genuine end-to-end MCP smoke test (CR-007)

## Status
Accepted — implemented and verified in this environment. `mvn verify`
(no `-DskipITs`) now genuinely runs Failsafe against a real `*IT.java`
test and reports it `Skipped: 1` (not silently absent); the same test
proven to genuinely attempt a connection and fail when pointed at an
unreachable host. The packaged-jar smoke test now drives a real MCP
JSON-RPC session and was proven able to fail (a deliberately broken
`METADATA_ONLY` run correctly caught the tool-count mismatch through
two independent checks).

## Date
Proposed 2026-08-15, Accepted 2026-08-15.

## Context
Items 5 and 6 in the 2026-08-14 external code review's own recommended
remediation order, naturally paired: both are about whether CI's claims
about itself are actually true.

- **CR-006**: `pom.xml`'s Failsafe plugin had no explicit `<includes>`,
  relying on the default pattern (`**/IT*.java`, `**/*IT.java`,
  `**/*ITCase.java`). Zero files in the repository matched - the three
  `*IntegrationTest.java` files run under **Surefire**
  (`**/*Test.java`), not Failsafe. `.github/workflows/ci.yml`'s own
  comment claimed `-DskipITs skips the *IT.java Failsafe suite, which
  requires a live DB2 instance` - describing a suite that did not
  exist. Confirmed directly before writing any fix: `find src/test
  -iname "*IT.java"` matched nothing, anywhere.
- **CR-007**: `scripts/smoke-test.sh` verified process startup, tool
  count, stdout cleanliness, the effective-config report, and graceful
  shutdown - but never sent a single MCP JSON-RPC message. It was a
  process-health check, not the "end-to-end MCP flow" both brief §11.2
  and the script's own header comment claimed.

## Decision drivers
- Must not just correct the comment - must make the claim true. A
  reworded comment describing an empty suite is still describing an
  empty suite.
- The live-DB2 gap (ADR 0009) is real and shouldn't be papered over -
  whatever closes CR-006 must still self-skip honestly when no DB2 is
  reachable, not fail CI or fake a pass.
- CR-007's fix must reuse the exact technique already trusted elsewhere
  in this project (the raw stdio JSON-RPC pipe used to verify ADR 0018
  by hand) rather than introduce a new MCP client dependency.
- Both fixes needed to be proven able to fail, not just observed to
  pass once - this project's standing discipline, applied here to its
  own test infrastructure rather than to application code for once.

## Considered options
1. **CR-006: add explicit Failsafe `<includes>`, add Maven Wrapper, and
   write one real, self-skipping live-DB2 `*IT.java` test.** Chosen.
   Makes the CI comment's claim literally true instead of just quieter
   about being false; gives the project owner something to literally
   run the moment their Db2 on Cloud instance (discussed earlier this
   session) is ready, without waiting for a future session to notice
   the gap again.
2. **CR-006 alternative: just fix the comment, don't add a real IT
   test.** Rejected: leaves the actual gap (no live-DB2 verification
   path exists at all) exactly where it was, just described more
   accurately. Cheaper, but doesn't close what CR-006 actually asks for.
3. **CR-007: extend `scripts/smoke-test.sh` to drive a real MCP session
   over the existing FIFO-based stdin plumbing, using `jq` for response
   validation.** Chosen. `jq` is present on GitHub-hosted `ubuntu-latest`
   runners by default and already available in this environment -
   confirmed before depending on it, not assumed. No new language
   runtime or MCP client library needed.
4. **CR-007 alternative: a JUnit-based MCP client test instead of a
   shell script.** Rejected for this pass: the packaged-jar smoke test
   specifically exists to test the *artifact* (real `java -jar`,
   real stdio, real process lifecycle) the way `mvn test` never does -
   folding it into JUnit would lose that distinction, which is the
   entire reason `scripts/smoke-test.sh` exists as a separate CI step.

## Decision
**CR-006**: Maven Wrapper added (`mvnw`, `mvnw.cmd`, `.mvn/wrapper/`,
generated via `mvn wrapper:wrapper -Dmaven=3.9.11`) - CI now runs
`./mvnw`, not a preinstalled `mvn`. Failsafe's `<configuration>` now
declares `<includes><include>**/*IT.java</include></includes>`
explicitly. New `Db2LiveConnectivityIT` (package
`adapter.db2`, matching the existing test layout): connects via plain
JDBC (`DriverManager`, the same `com.ibm.db2.jcc.DB2Driver` already a
main-scope dependency per ADR 0003), verifies `connection.isValid()`,
runs `SELECT 1 FROM SYSIBM.SYSDUMMY1`, and confirms `SYSCAT.SCHEMATA`
has at least one row - deliberately schema-agnostic, not assuming the
ITSM demo schema is loaded on whatever instance `DB2_HOST` names. Gated
by JUnit 5's `@EnabledIfEnvironmentVariable(named = "DB2_HOST", matches
= ".+")`, so it self-skips (not fails) whenever no live DB2 is
configured - true in CI and in this project's own development
environment today.

**CR-007**: `scripts/smoke-test.sh` now sends a real four-message MCP
session through the same FIFO already used to hold stdin open
(`initialize` → `notifications/initialized` → `tools/list` →
`tools/call` for `listSchemas`), paced with short sleeps after waiting
for Spring Boot's own `Started Db2McpServerApplication` log line. Every
line captured on stdout is validated as well-formed JSON-RPC via `jq`
(a stronger form of ADR 0001's "stdout is protocol-only" claim than the
previous "stdout is empty" check, which never sent any protocol traffic
to actually test against). The `initialize` response is checked for
`result.serverInfo`; `tools/list` for exactly 9 tools including
`listSchemas`; `tools/call` for a `result.content` shape (success or
controlled error both produce this, matching CR-007's own acceptance
criteria) - and the `tools/call` response body itself is also scanned
for credential leakage, a distinct check from the existing stderr-log
scan.

## Consequences
- **Verified, not just written.** `mvn verify` (no `-DskipITs`) run
  locally with no DB2 env vars set: `Db2LiveConnectivityIT` reports
  `Skipped: 1` in the Failsafe report - a real skip, not silent
  absence. The same test then run with `DB2_HOST=live-it-test-host
  .invalid` (a real, truthy, unreachable value): genuinely attempts a
  connection via the real IBM driver and fails with
  `SqlNonTransientConnectionException ... SQLSTATE=08001` - proof the
  gate is real, not a no-op that would pass regardless of DB2
  reachability.
- **The smoke test proven able to fail, not just observed to pass.**
  Run against a deliberately broken environment
  (`DB2MCP_CAPABILITY_PROFILE=METADATA_ONLY`, which registers 7 tools
  instead of 9): failed correctly, caught independently by both the new
  JSON-RPC `tools/list` response check and the pre-existing stderr log
  check (`"Registered tools: 9"` no longer found) - two layers agreeing
  on the same real failure, not one masking a gap in the other.
- **The real MCP response captured is genuinely informative.** The
  `tools/call` response to `listSchemas` (against the deliberately
  unreachable `smoke-test-host.invalid`) comes back as
  `{"errorCode":"DB2_EXECUTION_FAILED","message":"Metadata query
  failed","sqlState":"08001"}` - the exact sanitized shape ADR 0018/0020
  built, now observed automatically on every CI run instead of only
  when someone happens to check by hand.
- **CI still runs `-DskipITs`, unchanged in that respect.** `.github/
  workflows/ci.yml` continues to skip the Failsafe phase outright
  rather than let it run and report a skip - a deliberate choice, not
  an oversight: GitHub-hosted runners have no live DB2 either, so
  attempting the phase would only cost time to reach the same "skip"
  conclusion `-DskipITs` reaches immediately. A developer running the
  full `mvn verify` locally against a real DB2 instance (e.g. the
  project owner's Db2 on Cloud instance once live) gets the real check;
  CI's comment now describes this accurately instead of describing a
  suite that didn't exist.
- **Full suite: 154 total** (153 Surefire, up from 140 pre-ADR-0022, plus
  1 Failsafe test that self-skips without DB2) - all green under
  `mvn verify`.
- **Not addressed by this ADR**: `Db2LiveConnectivityIT` only proves
  connectivity and basic catalog readability - it does not exercise
  ADR 0022's `SPECIFICNAME`/`REFKEYNAME` claims, which need the ITSM
  schema loaded plus deliberately overloaded procedures and a
  unique-key-referencing foreign key. That remains separate, still-open
  live-DB2 validation work, named here rather than implied as covered.

## Verification / enforcement
`mvn verify` (no `-DskipITs`) locally: 154 tests, `Db2LiveConnectivityIT`
`Skipped: 1`. Same test with a bogus `DB2_HOST` set: genuine connection
attempt, genuine failure, correct SQLSTATE. `scripts/smoke-test.sh`
against the real packaged jar: full MCP session captured and validated;
the same script run against a deliberately misconfigured environment
correctly fails with exit code 1.

## Related decisions
ADR 0009 (Tier 1/Tier 2 live-DB2 honesty - `Db2LiveConnectivityIT` is
this project's first genuinely Tier 1-capable path for anything beyond
`executeQuery`/`lookupRefData`, even though it stays Tier 2 in this
environment), ADR 0018 (the raw stdio JSON-RPC technique CR-007's fix
automates), ADR 0016 (graceful shutdown - the smoke test's SIGTERM
isolation logic, unchanged, still relied on here), ADR 0021 (the
adopted portability extension - CR-006/CR-007 were next in the same
review's recommended order, after ADR 0020's P0 fixes, ADR 0022's Db2
correctness fixes, and ADR 0023's payload limits).
