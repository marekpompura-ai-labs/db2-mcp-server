# Implementation Plan

Target direction: `docs/master-brief.md` (Governed Db2 MCP Server).
Starting point: `docs/project-baseline-status.md` — documentation only,
zero implementation, verified by direct filesystem/Git audit.

This plan turns the brief's Phase 0–5 roadmap (§18) into concrete,
incrementally shippable steps for a single maintainer, each with a
verification command so "done" is checked against evidence, not
assumption — consistent with the brief's own instruction to the
implementation agent (§21.13) and this project's established habit of
verifying claims (see the driver-sourcing and baseline-status corrections
in the ADRs and baseline doc).

Each phase ends in a state that compiles, starts, and can be demonstrated
— no phase should leave the tree mid-refactor. Phases are ordered so that
later phases depend only on earlier ones, per the brief's dependency rule
(§4.2) and its instruction to order work by risk and dependency (§21.5).

---

## Phase 0 — Repository and build baseline

**Goal:** a buildable, empty-but-correct Spring Boot MCP server skeleton.
No tools yet.

**Deliverables:**
- `pom.xml` — Java 21, Spring Boot 3.4.5 parent, Spring AI 1.0.0 BOM,
  `spring-ai-starter-mcp-server`, `spring-boot-starter-jdbc`,
  `com.ibm.db2:jcc:11.5.9.0` from Maven Central (ADR 0003), version-pinned
  throughout — no unpinned ranges.
- `.gitignore` — `target/`, `config/.env`, `tests.output/`, IDE files.
- `config/.env.example` — placeholder credentials only.
- `src/main/resources/application.yml` — stdio, keep-alive, banner off
  (ADR 0001), DataSource pointed at env vars (ADR 0004).
- `src/main/resources/logback-spring.xml` — all logging to stderr (ADR
  0001).
- `bootstrap/Db2McpServerApplication.java` — `@SpringBootApplication`
  main class, no tools registered yet.

**Acceptance criteria / verification:**
```bash
mvn -q compile                 # compiles clean
mvn -q package                 # produces a fat jar
DB2_HOST=x DB2_PORT=50000 DB2_DATABASE=x DB2_USER=x DB2_PASSWORD=x \
  java -jar target/*.jar &     # starts, logs "Started Db2McpServerApplication"
                                # to stderr, nothing on stdout, process
                                # stays alive (keep-alive)
```
No real DB2 connection is required to start — Hikari only connects lazily
on first use, so a placeholder host is enough to prove the process stays
up correctly under stdio.

**Status:** in progress this session.

---

## Phase 1 — Java engineering foundation: first vertical slice

**Goal:** prove the layered architecture (§4.1–4.3 of the brief) with one
real tool end to end, not with empty placeholder packages. Package
boundaries and ArchUnit rules are added *as content needs them*, not
pre-created — creating nine packages with nothing in them yet would be
scaffolding for its own sake.

**Deliverables:**
- `mcp.contract.SchemaInfo` — typed record, not a generic map.
- `application.metadata.MetadataService` (port interface) +
  implementation.
- `adapter.db2.Db2MetadataAdapter` — `JdbcTemplate` against
  `SYSCAT.SCHEMATA`, excluding system schemas.
- `mcp.tool.SchemaTools` — `@Tool listSchemas()`, delegates to
  `MetadataService`; contains no JDBC or `DataSource` reference.
- `configuration.McpConfig` — registers `SchemaTools` as a
  `ToolCallbackProvider`.
- ArchUnit test: `mcp.tool` package must not depend on
  `org.springframework.jdbc.*` or `javax.sql.DataSource` — enforces the
  brief's dependency rule (§4.2) mechanically, not by convention.
- Unit test for the adapter's row-mapping logic (mocked `JdbcTemplate` —
  no live DB2 required to run this test).
- ADR 0005 — layer separation / ports-adapters, written from the real
  code, not speculatively.

**Acceptance criteria / verification:**
```bash
mvn -q test                    # ArchUnit + unit tests pass, no DB2 needed
```
Manual smoke test against a real DB2 instance (requires your
credentials, run by you, not verifiable in this environment):
```bash
claude mcp add --scope user db2 -e DB2_HOST=... ... -- java -jar target/*.jar
# then call listSchemas from Claude Code, expect real schema names back
```

**Status:** first slice complete and verified in this environment
(`mvn -q test` green: ArchUnit layer rule + adapter unit test; packaged
jar starts cleanly with `Registered tools: 1`, stdout stays empty). The
ArchUnit rule was confirmed to actually catch violations, not just pass
trivially — see ADR 0005. **Not yet verified:** a real DB2 connection —
`listSchemas()` has never been run against an actual database, only
compiled and unit-tested with a mocked `ResultSet`. That check is
pending until a live DB2 instance is available.

---

## Phase 2 — Remaining metadata tools

**Goal:** the other eight tools from the original spec, following the
Phase 1 pattern exactly (typed contract → port → adapter → `@Tool`), no
new architectural decisions needed.

**Deliverables:** `listTables`, `describeTable`, `getTableDependencies`,
`listProcedures`, `describeProcedure`, `getProcedureSource`,
`lookupRefData`, `executeQuery` — each with its typed result record(s)
from §5.2 of the brief, and its own unit test for row-mapping logic.
`executeQuery` additionally gets the `SELECT`/`WITH` prefix guard (ADR
0002 layer 3) and identifier-allowlist validation for `lookupRefData`.

**Acceptance criteria:** `mvn -q test` green; each tool individually
smoke-testable against the ITSM demo schema once Phase 6's demo
environment exists (or against any real DB2 instance in the meantime).

**Status:** implemented and verified in this environment — `mvn -q test`
green (31 tests), packaged jar starts with `Registered tools: 9` (the
full original tool set), stdout stays empty. SQL was written against
DB2 catalog view columns verified from IBM's documentation (not memory),
including using `SYSCAT.KEYCOLUSE` instead of `SYSCAT.REFERENCES`'
deprecated `FK_COLNAMES`/`PK_COLNAMES` columns. A unit test aimed
specifically at the `executeQuery` guard caught a real gap — a
prefix-only check lets `SELECT 1; DROP TABLE ...` through — fixed by
also rejecting any semicolon (see ADR 0002).

**Not yet verified — needs a live DB2 instance:** all SQL here is
unexercised against a real database. The catalog joins for
`getTableDependencies` in particular (FK/PK column pairing via
`COLSEQ`) are the highest-risk untested query in this phase; validate
that one first when a DB2 instance is available, ideally against the
ITSM demo schema's composite-key-free tables first, then anything with
a multi-column key if one gets added.

---

## Phase 3 — Security foundation

**Goal:** the brief's defense-in-depth model (§7) as enforced code, not
just documentation.

**Deliverables:**
- Capability profiles: `metadata-only` (default), `safe-query`,
  `advanced-query` (not enabled by default) — ADR 0006, later extended by
  ADR 0032 and ADR 0033.
- Structural SQL validation for `executeQuery` (prefer AST/structural
  parsing over regex-only per §7.3) plus resource limits (timeout, max
  rows, max payload bytes) — ADR 0007.
- Schema/table allowlists.
- `docs/security/adversarial-test-cases.md` + automated tests covering
  the catalogue in brief §7.6 (hidden writes, multi-statement, `FINAL
  TABLE`/`OLD TABLE`/`NEW TABLE`, non-allowlisted schema access, etc.).

**Acceptance criteria:** every adversarial test case is rejected with a
stable policy reason code; `mvn -q test` includes the adversarial suite
green.

**Status:** in progress.

- **Capability profiles (ADR 0006): done and verified.** `CapabilityProfile`
  (METADATA_ONLY/SAFE_QUERY/ADVANCED_QUERY) gates `executeQuery` at two
  points — tool registration in `McpConfig` and a check inside
  `Db2QueryAdapter` itself. Verified by actually starting the packaged
  jar under both settings: default profile registers 8 tools (no
  `executeQuery`), `DB2MCP_CAPABILITY_PROFILE=SAFE_QUERY` registers 9.
  `mvn -q test` green (49 tests total).
- **Structural SQL validation + row cap/timeout (ADR 0007): done and
  verified.** `SqlStructuralValidator` (JSqlParser 5.3) rejects anything
  that isn't a plain `Select`, layered on top of (not replacing) the
  existing regex guard — a deliberate change from the ADR's original
  `Proposed` plan, decided during implementation. `Db2QueryAdapter` caps
  results at 1000 rows (`QueryResult.truncated` flag) and a 30s query
  timeout, via its own dedicated `JdbcTemplate`. A test aimed at the
  validator's edge cases caught a real bug (empty string → NPE instead
  of a clean rejection) before it shipped. `mvn -q test` green (51
  tests); packaged jar smoke-tested under `SAFE_QUERY`.
- **Schema allowlist (ADR 0008): done and verified.** `SchemaAllowlistValidator`
  rejects any table reference outside `db2mcp.allowed-schemas` (empty by
  default — deny-all until configured) and rejects unqualified table
  references outright, since their resolved schema can't be verified
  statically. Written as `Proposed` before code, then `Accepted` after
  implementation - no surprises this time (unlike ADR 0007's null-parse
  discovery), `TablesNamesFinder` behaved as expected on quoted/mixed-case/
  multi-table cases. `mvn -q test` green (60 tests); packaged jar
  smoke-tested with `DB2MCP_ALLOWED_SCHEMAS=ITSM`. Known gap, not yet
  addressed: the allowlist only sees the queried statement's own text,
  so a view in an allowed schema that itself reads a disallowed schema
  would pass unnoticed.
- **Testing strategy without live DB2 (ADR 0009): done and verified.**
  Two-tier strategy: H2 (`MODE=DB2`) now gives real, non-mocked
  integration coverage for `executeQuery`/`lookupRefData` — the
  1000-row truncation cap and the schema allowlist were both verified
  against genuinely executing SQL and a genuinely existing
  out-of-allowlist table, not mocks. The seven `SYSCAT.*`-querying
  tools remain unverifiable this way and stay at their existing
  verification level (compiled, unit-tested with mocks, SQL
  cross-checked against IBM docs) until real DB2 access exists — this
  ADR does not and cannot close that gap. `mvn -q test` green (65
  tests); packaged jar confirmed not to bundle the test-scope H2
  dependency.
- **Adversarial test catalogue: done and verified.**
  `docs/security/adversarial-test-cases.md` maps all 14 scenarios from
  master brief §7.6 to actual tests. Writing tests aimed specifically at
  each scenario, rather than trusting existing coverage, found two real
  gaps that got fixed in the same pass: `SqlStructuralValidator` was
  never actually an independent layer against multi-statement input
  (the regex guard alone was doing that work — `CCJSqlParserUtil.parse`
  silently ignores text after the first statement; fixed by switching
  to `parseStatements` and requiring exactly one), and errors were not
  sanitized at all before this pass (a real DB2/H2 error would have
  leaked its raw driver message to the MCP client; fixed by catching
  `DataAccessException` and returning only a generic message + SQLSTATE).
  `QueryLimitsProperties` externalizes `maxRows`/`timeoutSeconds`
  (previously hardcoded, flagged in ADR 0007) — this is what made a
  real ~1-second timeout test possible. `mvn -q test` green (80 tests).
  ADR 0007 updated in place with these findings, per this project's
  practice of amending Accepted ADRs when new real findings arise
  (same as ADR 0002's semicolon note).
- **Still not started:** max payload bytes limit (still open, tracked
  in ADR 0007), query-complexity analysis beyond timeout, and
  log/metric/trace leakage testing (not applicable until Phase 4 exists)
  — all listed explicitly in the adversarial catalogue's "Known gaps"
  section rather than silently dropped.

---

## Phase 4 — Traceability and observability

**Goal:** brief §8–9 — correlation IDs, sanitized audit events,
Micrometer metrics, OpenTelemetry spans.

**Deliverables:** correlation ID per request, sanitized security-audit
log stream separate from the application log, `mcp.tool.*`/`db2.*`/
`security.policy.decisions` metrics, span hierarchy per §9.3, one example
dashboard or trace screenshot for the portfolio. Three ADRs: traceability
(ADR 0010), observability metrics (ADR 0011), and OpenTelemetry spans
(ADR 0012) — all written and Accepted.

**Acceptance criteria:** a rejected request and an allowed request are
both reconstructable from logs/traces without any raw SQL, credentials,
or row data appearing in them (checked by an explicit test, not manual
inspection alone).

**Status:** in progress — traceability, metrics, and spans done and verified; portfolio evidence not started.

- **Traceability (ADR 0010): done and verified.** Every `executeQuery`
  call gets a correlation ID and a real audit log line (decision,
  normalized SQL fingerprint — never raw SQL, duration, row count,
  truncation, SQLSTATE on failure) via a dedicated `AUDIT` logger routed
  to `logs/audit.log`, kept separate from the application log. Purely
  additive — none of the ~80 existing tests needed to change. Verified
  two ways: a real logback `ListAppender` attached to the `AUDIT`
  logger during tests (event content), and separately the packaged jar
  started for real to confirm the file-routing configuration itself
  actually works and nothing leaks into stderr (plain JUnit tests never
  trigger Spring Boot's `LoggingSystem`, so `logback-spring.xml`'s
  custom routing is invisible to them — a real, documented finding, not
  assumed).
- **Observability metrics (ADR 0011): done and verified.** Added
  `spring-boot-starter-actuator` for the `MeterRegistry` and
  auto-configured binders only — no web starter is present, so no HTTP
  endpoint exists. `mcp.tool.calls`, `mcp.tool.duration`,
  `mcp.tool.rejected`, `mcp.result.rows`, `db2.query.duration`,
  `db2.errors`, and `security.policy.decisions` all recorded for
  `executeQuery`, reusing ADR 0010's guard-stage structure rather than
  duplicating it. The "no network exposure stays intact" claim (a real
  tension the brief itself calls out in §9.4) was independently
  confirmed, not assumed: the real packaged process was inspected with
  `lsof` — zero TCP/UDP/LISTEN file descriptors out of 30 total open
  FDs. `mvn -q test` green (87 tests).
- **OpenTelemetry spans (ADR 0012): done and verified.** Genuine
  OpenTelemetry spans via `micrometer-tracing-bridge-otel`, exported as
  log lines (`opentelemetry-exporter-logging`) rather than to a live
  collector, since none is reachable from this environment (ADR 0009).
  Root `mcp.tool executeQuery` span with child spans for each guard
  stage plus `db2.execute`, built with the `Tracer`/`Span` API directly
  (not `Observation`, to avoid touching ADR 0011's already-Accepted
  `MetricsRecorder`). Verified with `SimpleTracer`: full span tree on
  success, correctly *partial* tree on schema rejection (`db2.execute`
  never starts), one shared trace ID per call. A real finding along the
  way: `SimpleTracer.getSpans()` returns start order, not end order —
  the first test version assumed the reverse and was wrong, fixed once
  actual behavior was observed. `brief.9.3`'s `result.redact`/
  `response.serialize` stages were deliberately not implemented as
  spans — no redaction logic exists in this adapter and serialization
  happens inside the Spring AI MCP framework, outside this class;
  fabricating empty spans for stages that do no real work was rejected.
  `mvn -q test` green (90 tests). Dependency resolution and the
  no-network-exposure claim were both re-confirmed against the real
  packaged jar, not assumed to still hold from ADR 0011.
- **Not started:** an example dashboard/trace screenshot for the
  portfolio, live end-to-end span output through an actual MCP client
  call (not drivable in this session — no way to run a real stdio
  handshake here), HikariCP metrics (assumed, not independently
  verified — doing so would need the exposed endpoint this phase
  deliberately avoided), and audit/metrics/span coverage for the eight
  metadata/lookup tools (scoped out deliberately — see ADR 0010/0011/0012).

---

## Phase 5 — Maintainability and manageability gates

**Goal:** brief §10–11 — the quality gates and contract-compatibility
protection that keep the project safe to keep changing.

**Deliverables:** MCP contract snapshot tests (ADR 0013), ArchUnit
expanded to full layer set, JaCoCo thresholds on policy code,
externalized/validated configuration with a safe effective-config
report, graceful shutdown.

**Acceptance criteria:** an unintentional tool-contract change fails the
build; `mvn -q verify` runs the full gate set green.

**Status:** done. All five listed deliverables complete (ADR 0013–0016);
`mvn verify` runs 113 tests green including the JaCoCo policy-package
gate. Not part of this phase's original deliverable list but worth
tracking separately if pursued later: broader code-quality tooling
(Maven Enforcer, Spotless/Checkstyle, SpotBugs/PMD, OWASP
Dependency-Check) — the brief explicitly cautions against adding tools
"for badge count," so this was deliberately left out rather than bolted
on to inflate the gate count.

- **ArchUnit expanded to full layer set — done.** `LayeringArchitectureTest`
  grew from the single ADR 0005 rule (mcp.tool must not touch JDBC) to
  five rules covering every dependency-direction invariant the layering
  implies: mcp.tool must not reach into `adapter` at all (not just JDBC),
  `application` must not depend on `adapter` (the port/adapter inversion
  the whole architecture rests on), `policy` must not depend on `adapter`,
  and `domain` must not depend on any other layer. All five were verified
  true of the actual codebase via grep before being written as rules, then
  confirmed passing (`mvn test`: 94 tests, up from 90).
  - **Real finding:** the `domain` rule was checked for genuine
    meaningfulness, not just written and trusted. Adding an *unused
    import* of `AuditLogger` to `PolicyDecisionReason.java` did **not**
    trip the rule — ArchUnit analyzes bytecode-level dependencies, and an
    unused import leaves no trace in bytecode. Replacing it with an actual
    field reference (`static final Class<AuditLogger> LEAK = ...`) did
    trip it, with a detailed violation report. Same "verify, don't assume
    framework behavior" pattern as ADR 0007's `parseStatements` finding
    and ADR 0012's `SimpleTracer` ordering finding — logged here since
    this one is about the test tooling itself, not the application code.
  - Full suite re-run after reverting the temporary probe: 94 tests, all
    green. Packaged-jar smoke test re-run twice — once at the default
    `METADATA_ONLY` profile (8 tools registered, `executeQuery` correctly
    excluded) and once at `SAFE_QUERY` (9 tools registered) — stdout 0
    bytes and zero `lsof` network file descriptors in both.
- **MCP contract snapshot tests — done (ADR 0013).**
  `McpContractSnapshotTest` builds the same `MethodToolCallbackProvider`
  `McpConfig` builds (but always with both tool objects, so all 9 tool
  contracts are pinned regardless of active capability profile),
  canonicalizes each `ToolDefinition`'s name/description/input-schema
  (sorted, so the golden file is stable across runs), and compares it
  against a checked-in golden file
  (`src/test/resources/mcp-contract-snapshot.json`). Proven to actually
  fail — not just assumed to — by deliberately editing `listSchemas`'s
  description, confirming a clear diff-bearing failure, then reverting.
  Full suite: 95 tests, all green.
- **JaCoCo coverage thresholds on policy code — done (ADR 0014).** A
  `PACKAGE`-scoped `jacoco-maven-plugin` `check` rule on
  `io.github.marekpompura.db2mcp.policy.*` (LINE 0.95, BRANCH 0.90),
  bound to `verify`. Building it surfaced a real methodology trap: a
  `BUNDLE`-element rule's include/exclude filters match the bundle's own
  name, not member classes, so scoping to a package that way silently
  disables the rule rather than erroring — caught only by the standing
  "raise the threshold to something impossible and confirm a real
  failure" discipline, which showed the trivial-pass instead of the
  expected failure. Fixed by switching to a `PACKAGE`-element rule.
  Along the way, closed two real gaps: `SqlFingerprint` had no dedicated
  test file at all (new `SqlFingerprintTest.java`, covering its
  null-input, unparseable-input, and parse-exception paths), and
  `SchemaAllowlistValidator` was missing direct tests for its own
  parse-failure and empty-statement branches. One branch left
  deliberately uncovered and documented as likely unreachable through
  any SQL JSqlParser accepts (`extractSchema`'s trailing-dot guard) —
  thresholds set to allow it rather than forcing a contrived test.
  Full suite: 103 tests, all green under `mvn verify -DskipITs`.
- **Externalized/validated configuration with a safe effective-config
  report — done (ADR 0015).** `spring-boot-starter-validation` added
  (hibernate-validator only, no servlet container); `maxRows`/
  `timeoutSeconds` now `@Positive`, `allowedSchemas` elements `@NotBlank`,
  `capabilityProfile` `@NotNull`, each proven to actually fail startup
  against the real packaged jar with a clear property/value/reason
  message. New `EffectiveConfigReporter` logs the safe effective config
  once at startup (stderr, never stdout). Two real findings along the
  way: the reporter's first version crashed application startup itself
  when `DB2_HOST`/`DB2_PORT`/`DB2_DATABASE` were unresolvable — an
  ordinary, tolerated state the app otherwise survives fine — fixed by
  catching the resolution failure instead of propagating it; and every
  earlier packaged-jar smoke test in this project's history (ADR 0005,
  0011–0014) had been setting a nonexistent `DB2MCP_DB_URL` variable, so
  none of them ever actually pointed at H2 — the real variables are
  `DB2_HOST`/`DB2_PORT`/`DB2_DATABASE`/`DB2_USER`/`DB2_PASSWORD`. Neither
  finding invalidates what those earlier smoke tests actually verified
  (tool count, no network exposure), but both are recorded rather than
  quietly fixed. Verified end-to-end with fake credentials
  (`DB2_USER=scott DB2_PASSWORD=tiger`): the datasource host/port/
  database resolve correctly in the report while `scott`/`tiger` appear
  nowhere in the entire stderr log. Full suite: 109 tests, all green.
- **Graceful shutdown — done (ADR 0016).** Real bug found while
  investigating this, not assumed away: the MCP SDK's
  `StdioServerTransportProvider` already detects stdin EOF and closes
  its own protocol session, but nothing tied that to the JVM actually
  exiting — `spring.main.keep-alive: true` (ADR 0001) keeps a non-daemon
  thread alive regardless of session state, so a disconnected client
  left the process running forever. Proven by starting the packaged jar
  with `< /dev/null`: it started, registered tools, and simply sat there
  indefinitely. Fixed with a replacement `stdioServerTransport` bean
  (`StdioLifecycleConfig`) wrapping `System.in` in
  `EofTriggeredShutdownInputStream`, which triggers
  `SpringApplication.exit()` + `System.exit()` on EOF, off the reader
  thread. Reverified: EOF now exits cleanly in ~4s (exit code 0);
  `SIGTERM` on a running instance (fed by an open pipe, not `/dev/null`)
  still exits in ~2s (code 143) — reconfirmed working, not just assumed
  unaffected. Added `spring.lifecycle.timeout-per-shutdown-phase: 10s` so
  shutdown itself is bounded. Full suite: 113 tests, all green.

---

## Phase 6 — Public portfolio packaging

**Goal:** brief §15–17 — the promotion-ready state, prepared inside the
private repo and then promoted to the public repository.

**Deliverables:** reproducible ITSM demo (schema init script, guided
scenarios), executive README draft, architecture/flow diagrams
(Mermaid, per brief §14), AI-development governance doc, threat model,
CI workflow, dependency/secret scanning. A "public/private separation"
ADR written once the promotion actually happens (both this and the
AI-development governance doc are still on `docs/adr/README.md`'s
"topics not yet covered" list — the ADR numbers referenced here in an
earlier draft of this plan were stale leftovers from before the
chronological renumbering and didn't correspond to those topics at all;
corrected here rather than left misleading).

**Acceptance criteria:** brief §19 "Definition of done" checklist passes
in full, evaluated against this repository.

**Status:** done. The work was completed inside the private repository
first, then promoted to the public repository as a curated, sanitized
snapshot (`docs/master-brief.md` §15.1).

- **Reproducible ITSM demo — done.** `docs/demo/itsm-schema.sql` — real
  DB2 DDL for all six brief-mandated objects (`REF_DATA`,
  `SERVICE_CATALOG`, `CUSTOMER`, `TICKET`, `TICKET_COMMENT`,
  `STP_FIND_TICKETS`), plus a least-privilege `ITSM_MCP_READONLY` role and
  grants (ADR 0002 layer 1) — fictional data throughout, no real people or
  employers. `docs/demo/guided-scenarios.md` maps the brief's six guided
  scenarios (§17.1) to exact tool calls and expected results, each marked
  Tier 1 (verified here) or Tier 2 (needs live DB2) — the same honesty
  split ADR 0009 established. Scenarios 4–6 aren't just described: a new
  `Db2DemoScenarioIntegrationTest` (H2, via a new `itsm-demo-h2-schema.sql`
  fixture kept separate from the general test suite's smaller one) proves
  the exact numbers the doc cites are real — `lookupRefData` resolving
  both `PRIORITY` and `STATUS` codes, `executeQuery` returning ticket 101
  for the open/high-priority query, and both named adversarial rejections
  (out-of-allowlist schema, multi-statement chaining) actually rejecting
  before touching the database. Full suite: 118 tests, all green.
- **Architecture and flow diagrams — done.** `docs/architecture-diagrams.md`
  covers all seven brief §14 diagrams, drawn against real code rather than
  the brief's generic sketch: span names in 14.2/14.5 were copied verbatim
  from `Db2QueryAdapter.executeQuery` (`capability.authorize` →
  `input.validate` → `sql.validate` → `scope.authorize` → `db2.execute`,
  confirmed via the source, not recalled from ADR 0012's prose), and the
  rejection reasons in 14.3 are the real `PolicyDecisionReason` enum
  values.
- **AI-development governance doc — done (ADR 0017).**
  `docs/ai-development/development-policy.md` covers brief §12's required
  workflow and AI-specific rules, each backed by a real example already
  in this repository's history (not a restated rule) — e.g. "AI must not
  invent supported Db2 behavior" points at ADR 0009's documented catch of
  exactly that failure mode (the false DBeaver driver-sourcing claim,
  corrected in ADR 0003). States plainly, rather than hiding, that brief
  §12.2's step 8 (formal PR review) isn't how this single-maintainer
  private-lab repository currently operates. §12.4's required "one
  sanitized example change lifecycle" uses a real commit (ADR 0016,
  graceful shutdown) chosen because it shows the workflow catching a
  genuine bug, not a clean success story.
- **Threat model — done.** `docs/security/threat-model.md`: trust
  boundaries (cross-referencing the architecture diagrams), assets, a
  12-row threat/mitigation table each citing an ADR and adversarial
  catalogue item, explicit non-goals (arbitrary writes, HTTP/OAuth,
  column-level classification, multi-tenant isolation), and residual
  risk carried from the adversarial catalogue's own "Known gaps" rather
  than restated independently. Writing it surfaced a real staleness bug
  in `docs/security/adversarial-test-cases.md`: item 13 (log/metric/trace
  leakage) was still marked "not applicable yet — pending Phase 4,"
  despite Phase 4 (ADR 0010–0012) having been complete for some time.
  Fixed by actually checking current coverage: audit-log leakage is
  genuinely tested (`Db2QueryAdapterIntegrationTest
  .executeQuery_writesRealAuditEventOnExecutionFailure`); metrics/span
  leakage is prevented by a closed, enum-based tag vocabulary (verified
  by reading `MetricsRecorder.java`/`Db2QueryAdapter.java`, not by a
  dedicated test) — recorded honestly as "covered by construction, not
  by a test that would fail if that changed," not upgraded to a full
  "Covered" it hasn't earned yet.
- **CI workflow and dependency/secret scanning — done.**
  `.github/workflows/ci.yml`: `build-and-test` runs `mvn -B verify
  -DskipITs` (the same full gate set — unit, ArchUnit, MCP contract
  snapshot, JaCoCo policy gate — used locally, minus the DB2-only
  Failsafe suite ADR 0009 already established can't run without live
  DB2) plus a package step and jar artifact upload; `secret-scan` runs
  `gitleaks/gitleaks-action`. `.github/dependabot.yml` adds weekly Maven
  and GitHub Actions dependency update checks — chosen over the OWASP
  Dependency-Check Maven plugin specifically to avoid a per-build NVD
  feed download that would be slow and flaky without an API key;
  GitHub's own hosted vulnerability database needs neither. YAML syntax
  validated locally (`python3 -c "import yaml; yaml.safe_load(...)"`)
  before pushing, then the workflow's actual first run checked via the
  GitHub Actions API — genuinely confirmed, not just assumed correct
  from local syntax validation: run 31682860131 completed with
  `conclusion: success` on real GitHub Actions infrastructure, both jobs
  green (`mvn verify` finished in 47s, gitleaks in 2s).
- **Executive README draft — done.** Root `README.md` rewritten using
  brief §16's suggested opening paragraph verbatim, capability bullets
  linking to their backing ADRs, a "Security by design" section that
  explicitly avoids unqualified "secure"/"production-ready" claims per
  §16.4's guidance (using the brief's own suggested qualified phrasing
  instead), a fast-start command block, the system diagram, an
  "Engineering quality" section, and — matching this project's standing
  honesty norm — an explicit "Honest limitations" section stated in the
  README itself, not left for a reviewer to find by reading the whole
  `docs/` tree. Every ADR link and doc link was checked against the
  actual filesystem before being left in (caught and fixed one real
  overstatement: an initial "18 ADRs" claim, corrected to the real count
  of 17 by counting `docs/adr/*.md`).

**Phase 6 status: all six listed deliverables done** — reproducible ITSM
demo, architecture diagrams, AI-development governance doc, threat
model, CI workflow with dependency/secret scanning, and the executive
README. Remaining before the brief §19 "Definition of done" checklist is
fully satisfied: the public/private separation ADR (written once
promotion actually happens, not before — consistent with this project's
"capture a decision at the point it's made" practice) and the actual
promotion/release step itself, which is a distinct, later action, not
part of building the private repository's content.

---

## Post-Phase-6 gap closure (brief §19 "Definition of done")

A direct status check against the brief's §19 checklist (rather than
assuming Phase 6's six deliverables meant "done") surfaced several real
gaps: the full structured error model, a tagged release/changelog, demo
media, an automated smoke test in CI, and a final confidentiality
review. Being closed one at a time, same discipline as every other phase.

- **Structured error model — done (ADR 0018).** `domain.error
  .ErrorCategory` (the ten brief §11.4 categories) and
  `domain.error.McpToolException`, whose message is a rendered JSON body
  (`errorCode`/`message`/`sqlState`) — verified by decompiling this
  project's exact Spring AI MCP dependency to confirm that's genuinely
  the entire channel an MCP client sees, not assumed. `Db2QueryAdapter`
  now throws it at every guard-chain rejection and execution failure,
  classifying SQLSTATE via a new `Db2SqlStateClassifier`
  (`57014`→`QUERY_TIMEOUT`, empirically the real SQLSTATE this project's
  own timeout test observes; `42501`/`42704`→DB2-specific categories,
  asserted from documentation, Tier 2 per ADR 0009). Verified end-to-end
  over a real MCP JSON-RPC session (`initialize` → `tools/call`), not
  just a unit test — the literal protocol response for a rejected query
  was observed and matches the design exactly. Full suite: 128 tests, up
  from 118.
- **Changelog — done; tag push — blocked in this environment, needs
  external action.** `CHANGELOG.md` (Keep a Changelog format) committed
  and pushed, explicit that these are private-lab internal milestones,
  not the brief §19 "first public release." A `v0.1.0` annotated tag was
  created locally at commit `f366422` but **could not be pushed to
  GitHub from this session** — a real, verified environment constraint,
  not a token-scope or repo-configuration problem: `git push origin
  refs/tags/v0.1.0` failed with HTTP 403, and a direct GitHub API call
  to create the ref got an explicit, unambiguous denial from this
  session's own outbound proxy ("Write access to this GitHub API path is
  not permitted through this proxy") — confirmed by testing a
  non-version-shaped tag name too, ruling out a `v*`-pattern tag
  protection rule on the repo. Branch pushes work fine through the same
  proxy; only tag/ref creation is blocked. **Recorded here and in
  `docs/ai-development/development-policy.md` as a standing constraint
  for future AI sessions working in this environment** — tag creation
  needs to happen via the GitHub web UI (Releases → Draft a new release)
  or from a machine with unrestricted git push access, not from inside a
  Claude Code remote-environment session. User was asked and chose to
  skip pushing the tag for now.
- **Health/readiness decision — done (ADR 0019).** New
  `Db2ConnectivityChecker`: a bounded database connectivity check logged
  once at startup (brief §9.4's explicit stdio-mode allowance —
  logs/metrics, not an HTTP endpoint, since none exists by design), never
  gating startup, bounded by the existing Hikari connection-timeout
  rather than a new property. Real bug found by testing against the
  packaged jar with a deliberately mismatched datasource: the first
  version only caught `SQLException`, but a Hikari configuration problem
  throws a plain `RuntimeException` that escaped the listener and
  crashed application startup entirely — fixed, then re-verified across
  three real scenarios (genuine H2 success via unit test; the
  configuration-error crash, now handled; a genuinely unreachable DB2
  host with the real driver, correctly logging `SQLSTATE=08001` with no
  credentials anywhere in the output). Full suite: 133 tests.
- **Automated smoke test in CI — done.** `scripts/smoke-test.sh`
  automates the manual packaged-jar verification this project's history
  has relied on by hand at every phase: 9 tools registered
  (`SAFE_QUERY`), stdout empty (ADR 0001), the effective-config report
  present (ADR 0015), the connectivity check present (ADR 0019), no
  credential leakage, and a clean exit on `SIGTERM` (ADR 0016) —
  isolated correctly from the stdin-EOF shutdown path, not conflated
  with it (a real mix-up caught while writing the script: killing the
  stdin feeder process before/alongside `SIGTERM` closed the pipe and
  let the process take the EOF path instead, silently testing the wrong
  thing — fixed by killing the feeder only after the app has already
  exited, and asserting the EOF log line is absent). Wired into
  `.github/workflows/ci.yml` right after packaging. Proven able to
  fail, not just assumed to work: run against a deliberately broken
  environment (profile unset, dropping tool count to 8), correctly
  failed with the exact expected diagnostic and exit code 1.
- **Demo screenshot — done.** `docs/demo/screenshots/allowed-vs-rejected.png`
  satisfies brief §19's "one allowed and one rejected flow demonstrated."
  The rejected panel is a genuine, live MCP JSON-RPC session captured
  verbatim against the packaged jar (same technique as ADR 0018's
  verification). The allowed panel's data comes from
  `Db2DemoScenarioIntegrationTest`'s real passing assertions and a real
  `mvn test` audit-log line — not a live packaged-jar capture, since that
  needs a reachable Db2 this environment doesn't have (spent real effort
  trying: injecting H2 into a packaged-jar copy via `BOOT-INF/lib` and
  `classpath.idx` got past the initial driver-loading error but not
  further; `mvn spring-boot:run -Dspring-boot.run.useTestClasspath=true`
  loaded H2 cleanly but was found to merge the child JVM's own
  `JAVA_TOOL_OPTIONS` startup message onto stdout — a real difference
  from the packaged jar's genuinely clean stdout, disqualifying it as a
  faithful stand-in). Each panel in the image states its own provenance
  rather than presenting both as equally "live" — consistent with this
  project's Tier 1/Tier 2 honesty norm (ADR 0009) applied to portfolio
  evidence, not just test coverage claims.
- **Final confidentiality/provenance review — done.** Swept every
  git-tracked file (not just the working tree — full `git log --all -p`
  history too) for real emails, IPs/hostnames, hardcoded credentials or
  connection strings, and company/employer-identifying material: none
  found. `application.yml`'s DB2 URL is env-var only, never a literal
  host. The only "IP-shaped" matches were the DB2 driver version string
  `11.5.9.0`; the only email is the project's own `noreply@anthropic.com`
  co-author trailer plus the repo owner's own git commit identity, both
  expected. The demo screenshot PNG was string-scanned and visually
  reviewed for anything unintentionally captured (real paths, hostnames) —
  clean, and each panel already self-labels its provenance. ITSM demo
  data (`itsm-schema.sql`) uses generic placeholder names, consistent
  with its own "no real people/employers/internal naming" header. One
  stale fact found and fixed while reviewing: README's ADR count still
  read "18" after ADR 0019 was added — corrected to 19.

---

## Post-v0.1.0: external code review response

After the v0.1.0 tag, the code was exported as a snapshot zip (`git
archive`) and sent for an independent external audit. It returned two
documents: a code review (`docs/security/code-review-2026-08-14.md`)
and a proposed master-brief extension for database portability
(`docs/master-brief-extension-database-portability.md`), motivated by a
real planned future consumer, **an AI incident-analysis client**, confirmed
directly with the project owner rather than accepted on the uploaded
document's word alone (see ADR 0021 for the full reasoning on why that
confirmation step mattered).

- **Review findings spot-verified before any fix was written.** Three
  of the review's claims were independently checked against the actual
  running code before being trusted: CR-001 (`lookupRefData` registered
  unconditionally in `McpConfig`, no schema allowlist anywhere in
  `Db2MetadataAdapter.lookupRefData`) — confirmed; CR-002
  (`Db2MetadataAdapter` had zero `catch` blocks) — confirmed by grep;
  CR-006 (Failsafe's default include pattern matches no file in the
  repo, despite CI comments claiming an `*IT.java` suite exists) —
  confirmed, zero `*IT.java` files exist anywhere. All three held up,
  which is why the rest of the review was treated as credible rather
  than independently re-verified line by line.
- **Two P0 findings closed — done, ADR 0020.** `lookupRefData` gated
  behind the same capability/schema/row/timeout machinery as
  `executeQuery`; every `Db2MetadataAdapter` method now sanitizes
  `DataAccessException`. Verified at three levels: unit/integration
  tests (140 tests, up from 133), a direct `McpConfigTest` proving tool
  registration counts differ correctly by profile (7 under
  `METADATA_ONLY`, 9 under `SAFE_QUERY`/`ADVANCED_QUERY`), and the real
  packaged jar's own startup log confirming the same counts live.
- **Database-portability extension adopted — done, ADR 0021.** Scope
  recorded as a formal decision, not silently absorbed from the
  uploaded file. Implementation phased per the extension's own required
  working method (close P0 findings first): Phase P1 (security boundary
  correction) is the only phase complete so far, via ADR 0020.
- **Phase P2 (Db2 correctness) — done, ADR 0022.** `SPECIFICNAME`-based
  procedure identity (`describeProcedure`/`getProcedureSource` now
  require `specificName`, not `procedureName` — a deliberate MCP
  contract change, golden snapshot regenerated and diff-reviewed) and
  `REFKEYNAME`-based foreign-key resolution in `getTableDependencies`
  (was incorrectly assuming the referenced key is always a primary key).
  Both are Tier 2 (ADR 0009): SQL corrected to match documented DB2
  catalog semantics, **not yet verified against a live DB2 instance** —
  the concrete first thing to check once real DB2 access exists (two
  procedures sharing a schema/name with different signatures; a foreign
  key referencing a unique constraint). Full suite still 140 tests — no
  new automated coverage was possible for either fix without live DB2,
  stated plainly rather than left implied.
- **CR-005 (response payload/cell-size limits) — done, ADR 0023.**
  `maxCellBytes`/`maxPayloadBytes` bound individual cells and total
  response size independently of `maxRows`; JDBC values normalized
  through a closed, safe representation set (CLOB bounded-read, BLOB
  explicit omission, unrecognized types → safe marker, never a raw
  `toString()`). Fully Tier 1 (unlike ADR 0022) — verified against real
  H2 `Clob`/`Blob`/`ARRAY` values. Full suite now 153 tests (13 new: 5
  integration, 8 unit for the UTF-8 truncation boundary math). No MCP
  contract snapshot change needed — `QueryResult`'s shape is unchanged,
  a deliberate simplification recorded in ADR 0023 rather than exposing
  new structured truncation metadata.
- **CR-006/CR-007 (honest CI test tiers, genuine MCP smoke test) —
  done, ADR 0024.** Maven Wrapper added, CI now runs `./mvnw`. Failsafe
  gets explicit `<includes>` and a real, self-skipping live-DB2 test
  (`Db2LiveConnectivityIT`, gated on `DB2_HOST` being set) — verified
  both ways: skips cleanly with no DB2 env vars (this environment, CI),
  and genuinely attempts a connection and fails with a real driver
  exception when pointed at an unreachable-but-configured host.
  `scripts/smoke-test.sh` now drives a real `initialize`/`tools.list`/
  `tools.call` MCP session over the packaged jar's stdio and validates
  every stdout line as well-formed JSON-RPC — proven able to fail
  (a deliberately broken `METADATA_ONLY` run correctly caught by two
  independent checks). CI's `-DskipITs` flag is unchanged (GitHub-hosted
  runners have no live DB2 either), but its comment is now accurate.
- **CR-008/CR-009/CR-012 (output contract snapshot, all-tool
  observability, correlation IDs) — done, ADR 0025.** All seven pure
  metadata tools now share the same audit/metrics/span treatment
  `executeQuery`/`lookupRefData` already had, via a shared
  `instrumented` helper; every `McpToolException` now carries a
  `correlationId` matching its audit line, verified to actually match
  (not just both existing) both in tests and live against the packaged
  jar's real MCP response; a second golden snapshot
  (`McpOutputContractSnapshotTest`) now protects every tool's output
  shape the same way ADR 0013 protects inputs, proven able to fail via
  a deliberate golden-file edit. A real test-design mistake caught
  while writing this (not shipped): a "success" audit test for
  `listTables` was wrong — that tool always fails against H2 (no
  `SYSCAT.TABLES` equivalent, ADR 0009) — fixed to test the outcome
  actually exercisable here. Full suite now 159 tests.
- **CR-011 (audit-file rotation) — done, ADR 0026.** The audit appender
  is now a rolling, bounded file (daily/10MB rollover, 30-day history,
  300MB total cap) at a configurable path (`db2mcp.audit.file`), plus an
  independent `AuditFileAvailabilityValidator` that fails startup fast
  if the destination isn't writable — verified against the real
  packaged jar. A genuinely surprising finding recorded rather than
  hidden: Spring Boot's own Logback error handling already aborts
  startup for the exact failure scenario tested, before the new
  validator ever gets a chance to run — kept anyway as an independent,
  documented, unit-tested layer, the same "no single layer sufficient
  alone" reasoning ADR 0002 already uses. Full suite now 162 tests.
- **Phase P3 (dependency inversion) — done, ADR 0027.** The nine result
  records moved from `mcp.contract` to `domain.model`; `MetadataService`/
  `QueryService` renamed to `MetadataPort`/`QueryPort` and relocated to
  `application.port.out`, now expressed entirely in domain-owned types
  instead of returning `mcp.contract.*` directly. Db2 SQLSTATE
  classification moved from a static utility in `domain.error` to
  `adapter.db2.Db2SqlStateClassifier`, implementing a new
  `DatabaseErrorClassifier` port. Two new ArchUnit rules, both proven able
  to fail before being trusted: `application_layer_must_not_depend_on_mcp_
  layer` (genuinely failed against the pre-refactor code, 9 real
  violations) and `database_error_classifiers_must_live_in_an_adapter_
  package`. Full suite now 164 tests; packaged-jar smoke test re-verified
  the identity mapping at the MCP boundary still serializes correctly.
- **Phase P4 (capability discovery and adapter selection) — done, ADR
  0028.** New `getCapabilities` tool (a tenth tool), always visible under
  every capability profile, backed by `CapabilityPort`/
  `Db2CapabilityAdapter`; reports effective capabilities/limits/database
  identity, with `productVersion` queried live from the JDBC driver
  rather than hardcoded. `db2mcp.database.product`
  (`DatabaseSelectionProperties`) makes Db2 adapter activation explicit;
  its single-value closed `DatabaseProduct` enum is the fail-fast
  mechanism for an unsupported value, verified live against the real
  packaged jar (invalid value → exit 1, clear property/value/reason
  message, zero stdout bytes). Both golden MCP contract snapshots and
  `scripts/smoke-test.sh` updated for the new tool, diffs reviewed
  before accepting. Also found and fixed a real, previously-shipped
  `NullPointerException` bug in `AuditEvent.allowed` (primitive-typed
  parameters unboxing a legitimately-null row count) - reproduced
  directly, confirmed two new regression tests fail against the original
  code and pass against the fix. Full suite now 174 tests.
- **Phase P5 (portability evidence) — done, ADR 0029.** A test-only
  `FakeDatabaseAdapter` (`MetadataPort`/`QueryPort`, deliberately not
  `CapabilityPort` — see ADR 0029) wired into the real `SchemaTools`/
  `QueryTools`/`ReferenceDataTools` classes and exercised end to end
  (`FakeAdapterMcpToolsTest`, 9 tests). Two genuinely reusable
  parameterized contract suites (`QueryPortContractTest`,
  `MetadataPortContractTest`) run identical structural assertions
  against both the real H2-backed Db2 adapters and the fake, for the two
  surfaces where that comparison is honestly possible (`QueryPort` in
  full, `MetadataPort.lookupRefData` only — the other seven
  `MetadataPort` methods are Tier 2, no dual-adapter claim made for
  them). Zero `src/main` changes — confirmed via `git status` before
  committing — proof that Phases P3/P4's dependency inversion needed no
  correction to support this. Two real test-writing mistakes caught and
  fixed while building this (a reused H2 database name across
  parameterized invocations; `@MethodSource` `Named<T>` unwrapping).
  Full suite now 191 tests. This closes all five phases of the adopted
  portability extension (ADR 0021) — only Phase P2's live-DB2
  verification remains open. README now carries the extension's own
  pre-authorized portfolio statement (§14), earned rather than assumed.
- **CR-010 (telemetry export demonstration) — done, ADR 0030.** New
  `ObservabilityExportConfig` beans — `LoggingMeterRegistry` and a
  `SimpleSpanProcessor`-wrapped `LoggingSpanExporter` — genuinely export
  every existing Micrometer metric and OpenTelemetry span to stderr,
  closing the gap between "instrumented" (ADR 0011, ADR 0012) and
  "exported and observable." Rejected the review's own network-listener
  and OTLP-collector alternatives as inconsistent with this project's
  no-network-exposure property (ADR 0001) and no-reachable-external-
  service constraint (ADR 0009); chose the review's own third,
  explicitly-named option instead. Four real bugs found and fixed along
  the way: the JUL-to-SLF4J bridge was never installed (spans logged via
  `java.util.logging`, silently going nowhere); the sampling-probability
  property was first placed in the wrong YAML namespace
  (`spring.tracing...` instead of `management.tracing...`); the default
  `BatchSpanProcessor` wasn't reliably flushing pending spans before this
  server's graceful shutdown (ADR 0016) exits the process; and this
  project's first `@SpringBootTest` exposed a genuine shutdown-hang bug
  in the real `stdioServerTransport` bean (a blocking `System.in` reader
  thread that can't be interrupted), intermittently failing the whole
  build past Surefire's 30-second patience — fixed by mocking the
  transport bean out of that one test. `scripts/smoke-test.sh` now
  asserts on both exports explicitly instead of only displaying a
  human-facing tail (which itself produced a false alarm during this
  work — `LoggingMeterRegistry`'s shutdown dump crowded an earlier span
  line out of the truncated display). Full suite now 198 tests.
- **ITSM demo schema and guided scenarios prepped for live-DB2
  verification.** `docs/demo/itsm-schema.sql` now includes the two
  objects ADR 0022's still-open claims need: `STP_FIND_TICKETS` is
  deliberately overloaded (`STP_FIND_TICKETS_BY_STATUS`/
  `STP_FIND_TICKETS_BY_CUSTOMER`, CR-003), and `SERVICE_CATALOG_NOTE`
  deliberately references `SERVICE_CATALOG`'s unique constraint
  (`SERVICE_NAME`) rather than its primary key (`SERVICE_ID`, CR-004).
  `docs/demo/guided-scenarios.md` gained a "Live-DB2 verification
  checklist" naming the exact tool call and exact expected result for
  each, plus `getCapabilities`'s live `productVersion` and the CR-010
  export lines - the concrete, checkable first thing to run the moment
  DB2 is reachable, not yet executed against a real instance.
- **Local Docker DB2 test-prerequisites runbook and schema/data split.**
  `docs/demo/live-db2-test-prerequisites.md` — a reusable, parameterized
  runbook for the common local case (DB2 via Docker Desktop + WSL2, CLP
  inside the container, not on the host), covering compose-up through
  handing off to the verification checklist. `itsm-schema.sql` split into
  structure-only DDL (tables, constraints, procedures, role/grants) and a
  new `itsm-test-data.sql` (the fictional seed data) - the two concerns
  no longer interleaved, so the "no real people/employers/internal
  naming" compliance boundary is visible at a glance. Both docs and the
  README updated to load both files in order.
- **ADR 0022 verified against live DB2 — done, Tier 2 → Tier 1
  (2026-08-17).** Run from a local Claude Code session against a real
  Docker DB2 instance (12.1.5.0), not this environment, which still has
  no live DB2 access. Every checklist item passed exactly as documented.
  Two real bugs found and fixed, both in `docs/demo/`'s demo assets, not
  application code: `itsm-schema.sql`/`itsm-test-data.sql` mixed `;` and
  `@` statement terminators, breaking the documented `db2 -td@`
  invocation outright (`SQL0104N`); `itsm-schema.sql`'s `GRANT USAGE ON
  SCHEMA` used a privilege DB2 LUW doesn't have (`SQL0557N`, removed).
  `guided-scenarios.md`'s `getCapabilities` example was also corrected
  (real `productVersion` is an internal `SQLvvrrmm` string like
  `SQL120150`, not the CLP-banner format originally shown).
  `Db2MetadataAdapter`'s actual `SYSCAT` SQL needed zero changes — both
  ADR 0022 mechanisms worked as designed on first real-instance contact.
  This was the project's last open item from the 2026-08-14 review; Gate
  B's "live-Db2 catalog behavior verified and documented" criterion is
  now met.
- **Stdin-EOF response-drop finding — investigated and fixed, ADR 0031.**
  Confirmed real by direct reproduction against the real packaged jar (no
  live DB2 needed - an unreachable `DB2_HOST` recreates the same timing
  window): two compounding causes, the MCP SDK's own stdio transport
  conflating "stop reading" with "stop writing" via a shared `isClosing`
  flag, and this project's own EOF-triggered `System.exit()` racing any
  in-flight tool call. A first fix attempt (delay `System.exit()` until
  drained) was verified insufficient on its own - the SDK's `isClosing`
  flag was already set regardless. Fixed by blocking the EOF callback
  itself until in-flight calls finish, before the SDK is ever allowed to
  observe EOF - first via a count-based tracker
  (`InFlightToolCallTracker`) gated by fixed settle-delay heuristics.
  That version was later found, empirically, to still fail intermittently
  on a contended CI runner (the heuristic's guessed delay wasn't always
  enough) and was replaced with an id-based deterministic tracker
  (`InFlightRequestTracker`, plus `RequestIdTrackingInputStream`/
  `ResponseIdTrackingOutputStream`) that marks a request's id
  pending/settled directly on the raw bytes as they're read/written, with
  no delay left to guess at. New regression script
  `scripts/eof-shutdown-race-test.sh`, confirmed to fail against the
  pre-fix code and pass against the fix - and confirmed passing 8/8
  consecutive runs after the deterministic rewrite. Full suite now 210
  tests.
- **Windows CRLF spuriously failing the MCP contract snapshot tests —
  investigated and fixed (issue #15).** Two compounding causes: no
  `.gitattributes`, so a Windows checkout with `core.autocrlf=true` could
  rewrite the checked-in golden JSON snapshots from LF to CRLF; and
  separately, Jackson's `DefaultPrettyPrinter` object indenter defaults to
  `System.lineSeparator()` (CRLF on Windows, LF on Linux/CI), independent
  of git entirely. Fixed both: a repo-wide `.gitattributes` (`* text=auto
  eol=lf`, with `mvnw.cmd` excluded via `-text` since it's a genuine
  Windows batch script), and an explicit LF object indenter in both
  snapshot tests. Verified via a byte-level diff against a from-scratch
  regenerated golden file, not just the test going green.
- **Remaining open item**: none from the 2026-08-14 review. A dedicated
  live-DB2 automated test (`Db2LiveConnectivityIT` currently only proves
  basic connectivity) covering the CR-003/CR-004 scenarios end-to-end,
  as opposed to the manual checklist just run, would still be worth
  writing at some point, but isn't required to close the review.

---

## Phase 7 (optional, private-only) — Advanced research

Explain-plan capture, query cost estimation, HTTP/Streamable transport
prototype, multi-database profiles, performance/red-team testing (brief
§18 Phase 5). Explicitly deferred; not required for a public release and
not started unless it becomes independently interesting. Sequenced in
`docs/research/phase5-roadmap.md`.

- **Thread 1 (advanced query policy) — done, ADR 0032.**
  `CapabilityProfile.ADVANCED_QUERY` was a no-op placeholder (identical to
  `SAFE_QUERY` everywhere); it now has its own, independently configured
  query limits (`AdvancedQueryLimitsProperties`, `db2mcp.advanced-query.*`),
  resolved once per adapter via a new `ResolvedQueryLimits.of(...)` so
  `Db2QueryAdapter`'s enforcement and `Db2CapabilityAdapter`'s reported
  `getCapabilities` manifest can never disagree. `EffectiveConfigReporter`
  (ADR 0015) updated to log the resolved, not base, numbers. No new DB2
  privileges, no schema-allowlist or SQL-validator relaxation - only the
  row/time/payload envelope changes, preserving "must never weaken
  database-account least privilege" (master brief §6.3). Folded in a small
  dedup of `Db2QueryAdapter`/`Db2MetadataAdapter`'s two byte-for-byte
  duplicated capability gates onto a single `CapabilityProfile.
  requireQueryExecutionAllowed(String)` method while both adapters were
  already being touched. Explain-plan and complexity/cost thresholds
  (threads 2-3) remain explicitly out of scope.
- **Thread 2 (explain-plan acquisition) - implemented, pending live-DB2
  verification, ADR 0033.** New `explainQuery` MCP tool, `ADVANCED_QUERY`-
  only, capturing DB2's access plan via `EXPLAIN PLAN FOR` and reading it
  back from `EXPLAIN_STATEMENT`/`EXPLAIN_OPERATOR`. Backed by a new
  `ExplainPort`, deliberately kept separate from `QueryPort` rather than
  added to it - `FakeDatabaseAdapter` (the portability-evidence fake, ADR
  0029) would otherwise need a meaningless stub or misleading fake
  implementation of a fundamentally DB2-only capability (ADR 0009: EXPLAIN
  tables are catalog machinery with no H2 equivalent). `Db2QueryAdapter`
  implements both ports, reusing its existing capability/structural/schema
  gates unchanged. `getCapabilities.capabilities.explainPlan` now `true`
  only under `ADVANCED_QUERY`. Full suite now 231 tests; MCP contract
  snapshots regenerated for the 11th tool. ADR 0033 status was `Proposed`
  at the time of this entry, not `Accepted` - the EXPLAIN_TIME-based
  correlation strategy for reading back "the plan just captured" was the
  one part flagged as possibly needing rework, not just verification, once
  run against real DB2. **Superseded: ADR 0033 is now Accepted, having
  been live-DB2 verified.** A new
  checklist item in `docs/demo/guided-scenarios.md` and a new §2b in
  `docs/demo/live-db2-test-prerequisites.md` (EXPLAIN.DDL provisioning)
  cover that verification step.
- **Thread 3 (query complexity/cost controls) — done, ADR 0034.** New
  `SqlComplexityValidator` walks the whole parsed statement tree (not just
  the top-level `FROM` clause — composing `ExpressionVisitorAdapter`
  reaches joins/subqueries hidden in `WHERE`/`HAVING`/`EXISTS`/`IN`, a gap
  a naive FROM-only implementation would have left open) and rejects cross
  joins unconditionally under every profile, capping join count and
  subquery nesting depth per profile
  (`QueryComplexityLimitsProperties`/`AdvancedQueryComplexityLimitsProperties`,
  `db2mcp.query-complexity.*`/`db2mcp.advanced-query-complexity.*`,
  resolved via `ResolvedComplexityLimits.of(...)` mirroring thread 1's
  pattern exactly). New gate (span `sql.complexity`) in both
  `executeQuery` and `explainQuery`, right after schema-allowlist
  authorization; new `PolicyDecisionReason.COMPLEXITY_EXCEEDED` for the
  audit trail, client-facing `ErrorCategory.VALIDATION_FAILED` unchanged.
  `getCapabilities.limits` now reports `maxJoins`/`maxNestingDepth` per
  profile. Full suite now 259 tests. Closes `docs/security/adversarial-
  test-cases.md` item 11 (previously "Partially covered" — timeout only).
  Unlike thread 2, needed no live-DB2 verification: this is a purely
  client-side, deterministic structural check that runs entirely before
  any SQL reaches DB2, so H2/mock-based unit tests plus a manual
  packaged-jar run (against a deliberately unreachable DB2 host, so a
  validation rejection is distinguishable from a downstream DB2 failure
  by error code alone) constituted complete verification. ADR 0034 status
  is `Accepted`. One narrower, honest gap remains out of scope: a scalar
  UDF that itself issues an expensive query inside DB2 is structurally
  invisible to any client-side SQL-text parse, the same category as ADR
  0008's view-indirection gap.
- **Thread 4 (performance and red-team testing) — done, ADR 0035.**
  Originally scoped as "test-only, no new ADR expected" — that changed once
  researching this thread's own two concerns (a concurrency test, and
  red-teaming the new explain-plan/complexity-control surfaces) surfaced
  three real gaps. New `HikariPoolConcurrencyIntegrationTest` (Surefire
  tier, not a new Maven phase) proves concurrent `executeQuery` calls
  beyond Hikari's `maximum-pool-size` either queue and succeed (bounded by
  `connection-timeout`) or fail with a sanitized `McpToolException` — new
  coverage, since only single-query timeout behavior had a test before.
  Two `SqlComplexityValidator`/`SqlStructuralValidator` bypasses closed: a
  structurally tautological `ON` clause (`JOIN ... ON 1=1`) evaded
  cross-join rejection (`ComplexityWalker.isTautological`, AND-decomposing
  but deliberately not OR-decomposing — a narrow, documented residual gap);
  and piped SQL syntax (`FROM t |> CROSS JOIN ...`) — independently
  verified against JSqlParser 5.3 directly, not assumed — parsed as a
  `Select` and bypassed structural validation, the schema allowlist, and
  complexity limits simultaneously, on the branch already pushed to
  remote. Master brief §7.5's "bound concurrent tool executions" was
  declared but never implemented anywhere — closed by a new
  `ToolExecutionAdmissionGate` (`policy/concurrency`, a non-blocking
  `Semaphore` wrapper, deliberately fail-fast rather than queuing, the
  opposite design from Hikari's own pool) wired into every registered tool
  uniformly via a new `AdmissionControlledToolCallback` decorator in
  `McpConfig` — one shared choke point, not a per-adapter duplicate.
  `db2mcp.concurrency.max-concurrent-tool-executions` (default 10);
  rejection gives `ErrorCategory.POLICY_REJECTED` its first real caller and
  a new `PolicyDecisionReason.CONCURRENCY_LIMIT_EXCEEDED` for the audit
  trail. No change to the MCP tool contract (both snapshot tests pass with
  no diff — tools are wrapped, not added/removed/reshaped). Full suite now
  287 tests. Closes `docs/security/adversarial-test-cases.md` items 17-18
  (new). ADR 0035 status is `Accepted`. Deliberately deferred, not fixed:
  the "breadth bomb" gap (many sibling subqueries/`UNION ALL` branches,
  each individually within caps, uncapped in aggregate) and `explainQuery`'s
  `EXPLAIN_TIME` correlation race (ADR 0033's own admitted gap, only
  testable live-DB2) — both recorded as known gaps rather than rushed into
  this pass.
- **Thread 5 (Dynatrace-specific integration) — implemented, pending
  live-Dynatrace-tenant verification, ADR 0036.** New `DynatraceExportProperties`
  (`db2mcp.dynatrace.*`, opt-in, `enabled` defaults `false`) and
  `DynatraceExportPropertiesValidator` (fail-fast `ApplicationRunner`,
  matching `AuditFileAvailabilityValidator`'s shape — refuses to start with
  `enabled=true` and a blank/non-https endpoint or blank token, and never
  interpolates the token value itself into its exception message). New
  `DynatraceObservabilityConfig` registers a real OTLP `SpanProcessor`
  (`opentelemetry-exporter-otlp`, `SimpleSpanProcessor` for the same
  shutdown-timing reason ADR 0030 already established) and a real
  Micrometer-native `DynatraceMeterRegistry` (`micrometer-registry-dynatrace`,
  Dynatrace's Metrics API v2 — deliberately not the generic OTLP metrics
  exporter, to sidestep its delta-temporality requirement entirely)
  additively alongside ADR 0030's stderr-only logging exporters —
  `@ConditionalOnProperty`-gated, so disabled means zero Dynatrace beans
  exist in the context at all. A real conflict found and fixed during
  implementation: merely adding `micrometer-registry-dynatrace` to the
  classpath activated Spring Boot's own `DynatraceMetricsExportAutoConfiguration`,
  colliding by bean name with this ADR's own registry — fixed by explicitly
  excluding that autoconfiguration on `Db2McpServerApplication`.
  `EffectiveConfigReporter` deliberately never wired to
  `DynatraceExportProperties` (exclusion by construction, the same
  standard already applied to DB2 credentials). Verified against a local
  fake HTTP server standing in for Dynatrace's ingest endpoints (JDK's
  `com.sun.net.httpserver.HttpServer`, no new test dependency) — correct
  request paths, exact `"Api-Token "` header format (not `"Bearer "`),
  real span/metric data reaching request bodies, and a dedicated
  leak-detection test confirmed to genuinely catch a leak (verified by
  temporarily reintroducing one) — plus a `@SpringBootTest` proving real
  Spring wiring end-to-end. No change to the MCP tool contract (both
  snapshot tests pass with no diff). Full suite now 307 tests. Also fixed
  in passing: a pre-existing, unrelated test-isolation bug in
  `Db2ConnectivityCheckerTest` that this thread's new `@SpringBootTest`
  classes happened to surface consistently. ADR 0036 status was `Proposed`
  at the time of this entry — the real Dynatrace-tenant round trip (actual
  ingestion, real token-validation behavior) could not be verified from
  this environment; tracked as a new checklist item in
  `docs/demo/guided-scenarios.md`. **Superseded: ADR 0036 is now Accepted,
  live-verified against a real Dynatrace trial tenant — a verification that
  found and fixed a genuine span-export defect.**
- **Thread 6 (HTTP transport and authentication prototype) — implemented,
  fully verified locally, ADR 0037.** New opt-in `db2mcp.http.*` config
  (`HttpTransportProperties`/`HttpTransportPropertiesValidator`, mirroring
  ADR 0036's shape exactly) hand-wires the MCP SDK's own
  `HttpServletStreamableServerTransportProvider` behind
  `spring-boot-starter-tomcat` + `spring-web` — deliberately not
  `spring-ai-starter-mcp-server-webmvc` or `spring-boot-starter-web`, to
  avoid a second competing MCP autoconfiguration module and the full
  Spring MVC stack. A real, confirmed constraint drove the design: this
  MCP SDK/Spring AI version's `mcpSyncServer`/`mcpAsyncServer` beans take
  exactly one transport-provider parameter (confirmed via `javap`), so
  `StdioLifecycleConfig`'s previously-unconditional stdio bean is now
  itself `@ConditionalOnProperty`-gated off whenever HTTP is enabled — a
  genuine XOR between two server modes, not simultaneous multi-transport.
  Authentication is a single static bearer token
  (`db2mcp.http.bearer-token`, ADR 0004's env-var pattern), checked by a
  new `BearerTokenAuthenticationFilter` (plain `jakarta.servlet.Filter`,
  constant-time `MessageDigest.isEqual` comparison, identical rejection
  for missing/malformed/wrong token, no MCP-protocol error envelope reused
  since nothing MCP-level exists yet at that point in the request) — not
  OAuth 2.1, and not per-client capability profiles (`CapabilityProfile`
  stays the single process-wide value it already is). Two real,
  independently-found Spring Boot/Spring Test gotchas fixed along the way:
  Boot 4.1 relocated `ServletWebServerApplicationContext` into a new
  `spring-boot-web-server` module, but it still extends `spring-web`'s
  `GenericWebApplicationContext` — `spring-web` had to be added explicitly,
  since `tomcat-embed-core` alone isn't enough; and `@DynamicPropertySource`
  values are added too late in Spring Boot Test's bootstrap sequence to
  influence `spring.main.web-application-type`'s effect on
  `WebApplicationType` selection, requiring `@TestPropertySource` for that
  one property in `HttpTransportIntegrationTest` (a test-only quirk, not a
  production-code issue — confirmed by direct empirical reproduction of
  the failure before fixing it, same discipline as every prior thread's
  found-and-fixed bugs). Verified with a real embedded Tomcat on a random
  port: a raw `HttpClient` probe confirms 401 on missing/wrong token, and
  the MCP SDK's own `HttpClientStreamableHttpTransport` client performs a
  genuine `initialize` handshake plus a real `getCapabilities` tool call
  end-to-end with the correct token. `HttpTransportDisabledIntegrationTest`
  proves the default (`enabled=false`) context is genuinely not a
  `WebApplicationContext` at all, not merely that the HTTP beans behave as
  no-ops. No change to the MCP tool contract (both snapshot tests pass
  with no diff). Full suite now 325 tests. ADR 0037 status was `Proposed` at the time of
  this entry (**superseded 2026-09-21: now Accepted within an experimental
  prototype boundary, after the dedicated review**)
  — unlike threads 2/5, everything here is fully locally verifiable (no
  live external system needed); it stays non-`Accepted` per the roadmap's
  own framing, pending a dedicated security review given HTTP transport's
  materially different risk profile (inbound network exposure) from every
  other thread in this phase.

- **Cross-model review governance adopted, and its first three P0
  findings closed — ADR 0038, ADR 0039.** A second AI role (ChatGPT)
  now performs independent implementation/security review of this
  repository against the master brief, formalized alongside the existing
  single-agent workflow (ADR 0017); every finding gets validated against
  the real codebase and classified `Confirmed`/`Rejected with
  evidence`/`Accepted risk`/`Deferred` before anything is acted on — see
  `docs/reviews/2026-09-01-master-brief-gap-review.md` and its response
  document. Of the review's 13 findings, the three P0s were fixed the
  same day: metadata tools bypassed the schema allowlist entirely
  (`Db2MetadataAdapter`'s six schema-taking methods now scope-checked,
  `listSchemas` now filters to it); configured byte/row limits weren't
  actually hard (`executeQuery`'s first-row payload exemption removed,
  real JSON-serialized byte accounting replaces a per-cell content sum,
  `Db2ValueNormalizer`'s truncation suffix now counts against its own
  budget, CLOB truncation now byte- not character-budgeted,
  `lookupRefData` and the metadata methods gained limits they previously
  had none of); and validator failures no longer forward raw
  parser/identifier detail into client-visible error messages
  (`EffectiveConfigReporter`'s startup log narrowed to safe
  categories/counts, the project's package logger no longer
  unconditionally `DEBUG`). Full suite now 364 tests. The remaining 9
  P1/P2 findings stay open, tracked in the response document, not
  silently expanded into this same change.

- **P1-2 (Bean Validation for MCP tool input contracts) closed — ADR
  0040, issue #39.** All 9 of the 11 `@Tool` methods that take parameters
  now validate a request record via a new `ToolInputValidator` before
  delegating to any port, rejecting blank/null required identifiers with
  a stable `VALIDATION_FAILED` error instead of letting bad input reach
  the adapter layer. An AOP-based (`@Validated` + method-parameter
  constraints) approach was investigated and rejected — this codebase's
  tool beans are invoked via `MethodToolCallbackProvider`'s reflection,
  not an ordinary Spring-managed call site, so whether a CGLIB proxy's
  copied methods still carry the `@Tool` annotation that provider looks
  for was exactly the kind of framework-internals question this project
  verifies rather than assumes; explicit in-method validation of an
  internally-constructed request record avoids the question entirely. No
  change to any tool's public signature or JSON input schema
  (`McpContractSnapshotTest` confirms zero diff). Full suite now 389
  tests. The remaining 8 P1/P2 findings stay open.

- **P1-3 (correlation ID propagation and a result-byte metric) closed -
  ADR 0041, issue #40.** `QueryResult`/`ExplainPlanResult` now carry the
  same `correlationId` their audit log line and every span in their call
  tree already did - previously only a *failed* call gave the caller
  anything to correlate, via `McpToolException`. Every root span
  project-wide (five call sites: `Db2QueryAdapter.executeQuery`/
  `explainQuery`, `Db2MetadataAdapter.lookupRefData` and its shared
  `instrumented` helper backing six other metadata methods,
  `AdmissionControlledToolCallback`'s concurrency-rejection span) is now
  tagged `correlationId`, not just `tool`. New
  `MetricsRecorder.recordResultBytes` (`mcp.result.bytes`) fires for every
  tool that returns data (9 of 11), reusing each caller's own
  already-computed byte total rather than recomputing it. No change to any
  tool's input contract (`McpContractSnapshotTest` confirms zero diff); the
  output contract for `executeQuery`/`explainQuery` gained exactly the one
  new field, reviewed as a diff (delete-golden-file-and-regenerate, not
  assumed safe) before being committed. Full suite now 391 tests (after
  reconciling with #39's own count on merge). The review's other two
  named sub-gaps (an explicit cancellation counter,
  re-checkable readiness) are explicitly deferred to issue #46 and ADR
  0019's existing trade-off respectively, per the issue's own recommended
  scope - not silently folded into this same change. The remaining 7
  P1/P2 findings stay open.

---

## Open decisions to confirm as we go

- **DB2 test instance access**: Phases 1–3's acceptance criteria that
  require a live DB2 connection can't be verified from this remote
  session — those steps are marked for you to run locally. Everything
  else (compilation, unit tests, ArchUnit, adversarial-test logic against
  a mocked adapter) is verifiable here.
