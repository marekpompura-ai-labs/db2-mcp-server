# Response: 2026-09-01 master-brief gap review

## Metadata
- **Review input:** `docs/reviews/2026-09-01-master-brief-gap-review.md`
  (ChatGPT architecture and review agent, 2026-09-01).
- **Validated by:** Claude Code, per the workflow formalized in ADR 0038 —
  every finding below was checked directly against the current branch's
  actual source and tests, not accepted on the review's own summary.
- **Human project owner:** Marco. This document records validation only;
  acceptance of findings and any remediation scope/priority decision is
  the human project owner's, per ADR 0038 step 7.
- **Scope of the validation change:** Validation only. Per explicit
  instruction, no remediation of any finding below was included in the
  same change as the governance documentation this document is part of —
  see `docs/reviews/` and ADR 0038 for the process this document follows.
- **Remediation update (2026-09-01, separate change):** the three P0
  findings (P0-1, P0-2, P0-3) have since been fixed, per explicit
  follow-up instruction, in a change kept separate from the governance
  documentation above. See ADR 0039 for the full decision record and each
  finding's own section below for what changed and how it was verified.
  All other findings remain open/undecided exactly as validated below.

## Classification legend
- **Confirmed** — reproduced directly against the current branch's source
  and/or tests; the finding is factually accurate.
- **Rejected with evidence** — investigated and found not to hold, or to
  be materially inaccurate, against the current branch.
- **Accepted risk** — confirmed as a real, current gap, but one this
  project has already deliberately documented and accepted for its
  current phase (e.g. a private, non-promotable prototype with a stated
  gating decision already on record) rather than a newly surfaced,
  undecided gap.
- **Deferred** — confirmed as a real gap, but explicitly out of scope for
  the current phase by the review's own framing or an existing project
  decision (e.g. gated on public promotion, which has not happened).

---

## P0-1 — Metadata tools bypass the configured visibility scope

**Classification: Confirmed.**

Reproduced exactly as described:
- `Db2MetadataAdapter.listSchemas()` (lines 223-227) calls `LIST_SCHEMAS_SQL`
  (lines 81-87) with no allowlist intersection — it excludes only
  `SYS%`/`NULLID`/`SQLJ`.
- `listTables`, `describeTable`, `getTableDependencies`, `listProcedures`,
  `describeProcedure`, `getProcedureSource` (lines 229-286) pass
  caller-supplied schema/table/procedure names directly into JDBC queries
  with no `SchemaAllowlistValidator` call anywhere in their bodies.
- The shared `instrumented()` helper's own javadoc (lines 408-417) states
  outright: "None of them are capability- or scope-gated (they are visible
  under every profile...)".
- `McpConfig.schemaToolCallbackProvider` (`configuration/McpConfig.java:26-71`)
  adds `tools.add(schemaTools)` (line 39) unconditionally, before any
  capability-profile branching — all 7 metadata tools register under every
  profile, including `METADATA_ONLY`.
- `SchemaAllowlistValidator` (`policy/sql/SchemaAllowlistValidator.java`)
  has exactly 3 call sites project-wide: `Db2MetadataAdapter.java:339`
  (only `lookupRefData`), and `Db2QueryAdapter.java:142,287`
  (`executeQuery`/`explainQuery`). None of the 7 pure metadata methods
  call it.

No remediation is included in this change — this document records
validation, not the fix.

**Remediation (2026-09-01, ADR 0039, issue #34 — filed retroactively
after the fix, see the process note in ADR 0039):** Fixed. `Db2MetadataAdapter
.instrumented` gained a schema-scope check applied to all six methods
listed above; `listSchemas` now filters its result to
`SchemaAllowlistValidator.isAllowed`. New test
`Db2MetadataAdapterSchemaScopeTest` (11 cases) proves each method rejects
a schema outside the allowlist before touching DB2/H2 at all, that an
empty allowlist rejects everything (the same fail-closed default
`executeQuery` already had), and that `listSchemas` returns only
allowlisted schemas from a wider synthetic catalog. A table/object-level
allowlist remains open — deliberately not added in this pass, see ADR
0039's Decision section.

## P0-2 — Configured byte limits are not hard and do not cover all data-returning tools

**Classification: Confirmed.**

Reproduced exactly as described:
- `Db2QueryAdapter.java:166-187` — `if (!rows.isEmpty() && payloadBytes +
  rowBytes > maxPayloadBytes)`: the first row is always admitted
  regardless of size, with an explicit code comment justifying the choice.
- Byte accounting sums only per-cell content (`cell.approximateBytes()`),
  never JSON structural overhead (brackets, commas, field names, quoting).
- `Db2ValueNormalizer.boundedString()`/`boundedClob()`
  (`Db2ValueNormalizer.java:109-123`, `94-107`) both append
  `TRUNCATED_SUFFIX` *after* truncating to budget — the returned string can
  exceed `maxCellBytes` by the suffix's ~45-50 bytes.
- CLOB budget (`Db2ValueNormalizer.java:100`) is a character count passed
  to `clob.getSubString(1, charBudget)`, not a UTF-8 byte budget — an
  in-code comment (lines 96-99) admits non-ASCII overage.
- BLOB/unsupported-type marker strings (lines 30, 66-73, 90-91) bypass
  `maxCellBytes` bounding entirely.
- `maxCellBytes`/`maxPayloadBytes` never appear in `Db2MetadataAdapter.java`
  (confirmed by grep) — the 7 pure metadata methods use a plain, unbounded
  `JdbcTemplate`; only `lookupRefDataJdbcTemplate` gets
  `setMaxRows`/`setQueryTimeout`, and even those are always SAFE_QUERY-tier
  (see P1-1). `getProcedureSource`'s `TEXT` column is fully unbounded
  (`rs.getString("TEXT")`, zero normalization).

No remediation is included in this change.

**Remediation (2026-09-01, ADR 0039, issue #35 — filed retroactively
after the fix):** Fixed. `executeQuery`'s first-row
payload exemption removed (a budget smaller than one row's content now
returns zero rows, `truncated: true`); payload accounting now uses real
JSON-serialized byte length (`JsonPayloadSizer`), not a per-cell content
sum; `Db2ValueNormalizer`'s truncation suffix now counts against
`maxCellBytes` itself, not appended on top of it; `boundedClob` reads
`maxCellBytes + 1` characters and applies real UTF-8 byte accounting,
closing the character-vs-byte gap; BLOB/unsupported-type markers are now
bounded too. `lookupRefData`'s cells are now bounded and its accumulated
response is payload-byte-capped (`truncated` reflects either cap); the
seven pure metadata methods now have a row cap and query timeout;
`getProcedureSource`'s `TEXT` is now bounded. Regression tests added/
updated in `Db2ValueNormalizerTest`, `Db2QueryAdapterIntegrationTest`
(including a dedicated multi-byte-CLOB case), `Db2MetadataAdapterRowLimitTest`,
and `Db2MetadataAdapterLookupRefDataIntegrationTest` — each confirmed to
fail against the pre-fix code. The response envelope's structural
overhead (column descriptors, wrapper keys) and per-list-tool
truncation-reporting on the MCP-facing return shape remain open,
explicitly not chased in this pass — see ADR 0039.

## P0-3 — Sensitive-detail sanitization is incomplete

**Classification: Confirmed.**

Reproduced exactly as described, with the client-visibility mechanism
independently traced end to end:
- `McpToolException` renders its constructor arguments to JSON at
  construction time; that JSON becomes `getMessage()`, which is "the
  entire channel an MCP client sees" per the class's own javadoc. No
  intervening catch block in `QueryTools.executeQuery`,
  `ReferenceDataTools.lookupRefData`, or
  `AdmissionControlledToolCallback.call` rewrites or discards that message
  before it reaches the client.
- `SqlStructuralValidator.java:43-44` concatenates raw
  `JSQLParserException.getMessage()` into a thrown message;
  `Db2QueryAdapter.java:132-139` (and the equivalent block in
  `explainQuery`, lines 270-284) forwards it verbatim into
  `McpToolException` — client-visible.
- `SchemaAllowlistValidator.java:38-43,56-63` embeds the caller's raw
  schema/table-reference string in its rejection message;
  `Db2QueryAdapter.java:141-147` and `Db2MetadataAdapter.java:338-351`
  both forward it verbatim — client-visible.
- `Db2MetadataAdapter.requireIdentifier` (lines 503-507) embeds the raw
  caller-supplied identifier; `lookupRefData`'s `input.validate` catch
  block (lines 315-336) forwards it verbatim — client-visible.
- `describeProcedure`/`getProcedureSource` not-found paths
  (`Db2MetadataAdapter.java:263-274,277-286`) embed the caller-supplied
  schema/specificName verbatim, forwarded via `instrumented()`'s
  `NotFoundException` catch (lines 431-435) — client-visible, though
  qualitatively distinct: the caller already knows the identifier they
  themselves queried for, so this is not a hidden-object-disclosure risk
  the way the other three are.
- `EffectiveConfigReporter.java:87-101` logs `allowedSchemas`,
  `auditFile`, and `datasourceUrl` in plain text; `redactCredentials`
  (lines 55, 115-125) only strips a `user:pass@` userinfo segment this
  project's URL format never actually contains, so it is a no-op against
  hostname/port/database-name in practice — log-only (startup listener,
  not reachable from any tool call), not client-visible, but a real gap
  against §7.4/§9's telemetry-privacy requirement.
- `Db2ConnectivityChecker.java:66` logs a raw exception (message + stack
  trace) at DEBUG on any non-`SQLException` `RuntimeException`;
  `logback-spring.xml:36` sets the entire `io.github.marekpompura.db2mcp`
  package to DEBUG unconditionally (no profile guard) — log-only, but
  on-by-default with no opt-out.
- Both `STDERR` and `AUDIT_FILE` appenders (`logback-spring.xml:3-8,19-30`)
  use plain `PatternLayoutEncoder` text, not structured JSON — confirmed,
  no JSON encoder configured anywhere in the file.

No remediation is included in this change.

**Remediation (2026-09-01, ADR 0039, issue #36 — filed retroactively
after the fix):** Fixed. `SqlStructuralValidator`'s
and `SqlComplexityValidator`'s (structural/complexity validation),
`SchemaAllowlistValidator`'s (schema/table-reference rejection), and
`requireIdentifier`'s (`lookupRefData` identifier validation) exception
messages are no longer forwarded to the MCP client anywhere in
`Db2QueryAdapter`'s or `Db2MetadataAdapter`'s guard chains — each now
throws a stable, generic message instead. `CAPABILITY_DISABLED` catches
were left unchanged (confirmed safe: `CapabilityProfile`'s messages
interpolate only a fixed tool-name constant and the enum's own name).
`describeProcedure`/`getProcedureSource`'s not-found messages were also
left as-is, per this document's own qualitative distinction above (the
caller already knows the identifier they queried for) — documented as a
deliberate decision, not an oversight. `EffectiveConfigReporter` now logs
`allowedSchemaCount`, `auditFileConfigured`, and `datasourceScheme` in
place of the three flagged fields; the now-dead `redactCredentials` was
removed. `logback-spring.xml`'s project-package logger changed from
unconditional `DEBUG` to `INFO`, matching root — `Db2ConnectivityChecker`'s
raw-exception detail log is no longer on by default. New sentinel-based
adversarial tests in `Db2QueryAdapterIntegrationTest` and
`Db2MetadataAdapterLookupRefDataIntegrationTest` assert the client-visible
message never contains the sentinel token, for both a malformed-SQL parse
failure and a schema-rejection case. Structured-JSON operational logging
remains open, not addressed here — a separate, larger logging-format
decision.

## P1-1 — `lookupRefData` does not honor `ADVANCED_QUERY` effective limits

**Classification: Confirmed.**

`Db2MetadataAdapter`'s constructor (lines 197-221) is injected with
`QueryLimitsProperties` only — never `AdvancedQueryLimitsProperties` or
`ResolvedQueryLimits` — so `lookupRefData`'s row limit/timeout (lines
211-214) are always SAFE_QUERY-tier, unlike `Db2QueryAdapter`, which
correctly resolves limits via `ResolvedQueryLimits.of(capabilityProfile,
...)`. This is a genuine disagreement between what
`AdvancedQueryLimitsProperties`/`getCapabilities` advertise and what
`lookupRefData` actually enforces.

No remediation is included in this change.

**Remediation (2026-09-01, ADR 0039):** Fixed as a direct consequence of
implementing P0-2's `lookupRefData` cell/payload bounding correctly —
doing that required resolving limits through the same
`ResolvedQueryLimits` abstraction `Db2QueryAdapter` uses, which
necessarily also fixes this tier disagreement. `Db2MetadataAdapter`'s
constructor now takes `AdvancedQueryLimitsProperties` and resolves
`maxRows`/`timeoutSeconds`/`maxCellBytes`/`maxPayloadBytes` via
`ResolvedQueryLimits.of(capabilityProfile, ...)`. Not treated as
independently expanded scope — see ADR 0039's Decision section.

## P1-2 — Tool input contracts and Bean Validation are not implemented as specified

**Classification: Confirmed.**

All 11 `@Tool` methods take raw scalar `String` parameters (e.g.
`QueryTools.java:19`, `ReferenceDataTools.java:27-35`'s six-parameter
`lookupRefData`), never a request record/DTO. `@Valid` appears zero times
anywhere in `src/main/java` (grep-confirmed). Every `@NotBlank`/`@Positive`/
`@NotNull` annotation found is on a `@ConfigurationProperties` record
(`QueryPolicyProperties`, `AuditProperties`, `AdvancedQueryLimitsProperties`,
`QueryComplexityLimitsProperties`, `AdvancedQueryComplexityLimitsProperties`,
`QueryLimitsProperties`, `ConcurrencyLimitsProperties`,
`CapabilityProperties`, `DatabaseSelectionProperties`) — none in
`mcp/tool/`. `pom.xml:180-183`'s own comment scopes
`spring-boot-starter-validation` to "Bean Validation for
`@ConfigurationProperties`", confirming this was a deliberate scope, not
an oversight limited to tool input specifically — but the master brief's
requirement for validated tool input contracts remains unmet.

**Remediation (2026-09-06, ADR 0040, issue #39):** Fixed. All 9 of the 11
`@Tool` methods that take parameters (`listSchemas`/`getCapabilities` take
none) now validate a request record via a new `ToolInputValidator` before
delegating to any port, rejecting blank/null required identifiers with a
stable `VALIDATION_FAILED` error instead of letting bad input reach the
adapter layer. No tool's public signature or JSON input schema changed
(`McpContractSnapshotTest` confirms zero diff) — see ADR 0040's Decision
section for why an AOP-based (`@Validated` + method-parameter constraints)
approach was investigated and rejected in favor of explicit in-method
validation instead.

## P1-3 — Traceability and observability are present but not end-to-end complete

**Classification: Confirmed**, sub-point by sub-point:

- **Correlation ID error-only:** `QueryResult`/`ExplainPlanResult` records
  have no `correlationId` field; only `McpToolException` carries one.
  Confirmed.
- **Spans not tagged with correlation ID:** full inventory of every
  `span.tag(...)`/`rootSpan.tag(...)` call in the codebase found only
  `"tool"`, `"outcome"`, `"reason"` tags — never `correlationId`.
  `AuditEvent` has no `traceId`/`spanId` field. Confirmed.
- **`mcp.result.bytes` missing:** `MetricsRecorder.java` (full file, 70
  lines) defines `mcp.tool.calls`, `mcp.tool.duration`, `mcp.tool.rejected`,
  `mcp.result.rows` (rows, not bytes), `db2.query.duration`, `db2.errors`,
  `security.policy.decisions` — no byte-size meter. Confirmed.
- **No explicit cancellation/timeout counter:** zero `.cancel(` calls
  project-wide; `PolicyDecisionReason` has no `TIMEOUT`/`CANCELLED` value;
  timeouts surface only as a generic `db2.errors{sqlstate=57014}`
  increment. Confirmed.
- **Readiness is one-time, not queryable:** `Db2ConnectivityChecker.java:41-42`
  runs once on `ApplicationReadyEvent`, log-only, never re-checkable — and
  ADR 0019 (lines 49-56, 63-71) confirms this is deliberate design (a real
  `/health` endpoint was explicitly considered and rejected to preserve
  zero-network-exposure), not an oversight. Confirmed as a real gap against
  the master brief's queryable-readiness framing, while noting the project
  has an on-record rationale for the trade-off.

**Remediation (2026-09-06, ADR 0041, issue #40):** Partially fixed, per the
issue's own recommended scope. Closed: `QueryResult`/`ExplainPlanResult`
now carry `correlationId`; every root span project-wide is now tagged
`correlationId`, not just `tool`; a new `mcp.result.bytes` metric fires for
every tool that returns data. Deliberately not closed here, per the
issue's own text: `AuditEvent.traceId`/`spanId` (a materially larger,
separately-scoped change from correlation-ID propagation); the explicit
cancellation/timeout counter (deferred to issue #46, since a counter for a
capability that doesn't exist yet would be premature); re-checkable
readiness (ADR 0019's own deliberate, already-documented trade-off, not
reopened here).

## P1-4 — `explainQuery` needs an explicit exception to the "read-only" story

**Classification: Confirmed.**

`Db2QueryAdapter.captureAndReadPlan` (lines 386-388) executes a real
`jdbcTemplate.execute("EXPLAIN PLAN SET QUERYTAG = ... FOR " + sql)` call
that writes rows into `EXPLAIN_STATEMENT`/`EXPLAIN_OPERATOR` — a genuine
write, requiring privilege beyond pure `SELECT`. The adapter's own javadoc
(lines 239-241) calls these "catalog tables" and frames the operation as
"[n]othing is executed for real," which — while accurate about the
explained statement's business logic never running — conflates that with
the write side effect the EXPLAIN PLAN statement itself performs. A
narrower acknowledgment does exist nearby (lines 236-238: skipping
read-only validation for the explained statement "is still a policy
exception, not a harmless no-op"), but it addresses statement validation,
not the catalog-table write. `ExplainPlanResult.statementText`
(`domain/model/ExplainPlanResult.java:13`,
`Db2QueryAdapter.java:415-419`) does return the raw, caller-supplied SQL
unredacted and unfingerprinted, unlike the audit path (which only ever
gets `sqlFingerprint.of(sql)`).

No remediation is included in this change.

**Remediation (2026-09-06, issue #41 — documentation and comment fixes
only, no behavior change):** Fixed both halves of this finding. The
catalog-table write is now an explicit, documented exception to the
read-only guarantee in three places: README.md's read-only bullet, ADR
0033's own Consequences section, and `docs/security/threat-model.md`'s
"Out of scope" section (all bounded explicitly to
`EXPLAIN_STATEMENT`/`EXPLAIN_OPERATOR`, never the caller's schema data).
`Db2QueryAdapter.explainQuery`'s javadoc no longer says "[n]othing is
executed for real" without qualifying that only the caller's own SQL
never runs — the write itself is now named directly. `statementText`'s
verbatim-return behavior was a deliberate decision, not an oversight;
that decision and its rationale (no confidentiality boundary crossed by
echoing text the caller themselves supplied) are now recorded directly
in `ExplainPlanResult`'s own javadoc. No code behavior changed by this
remediation, only comments/docs.

## P1-5 — Several planned quality gates are absent

**Classification: Confirmed**, with one sub-item noted as effectively
**Deferred** rather than newly open.

`pom.xml` (full file, 456 lines) and `.github/workflows/ci.yml` (full
file) contain no Maven Enforcer, no Spotless/Checkstyle, no
SpotBugs/PMD, and no dependency-vulnerability-scan plugin or CI job.
Dependabot (`.github/dependabot.yml`) configures version-update PRs only,
not a vulnerability gate. JaCoCo is scoped to `policy.*` only (`pom.xml:386-452`),
not project-wide. No performance baseline exists. None of these gaps is
recorded anywhere as a deliberate rejection (an ADR or master-brief note
saying "not doing this and here's why") — they are simply unimplemented.
Confirmed as genuinely open.

The one exception: "tagged release/public release evidence" — the review
itself frames this as "expected to remain open while no public repo
exists" (`docs/master-brief.md` §15.1, ADR 0037's own promotion gate).
That sub-item is **Deferred**, not newly open — it is already accounted
for by the project's existing private-phase framing, not a gap this
review newly surfaced.

No remediation is included in this change.

**Remediation (2026-09-06, issue #42 — partial, by explicit scope
decision):** One of the four named gates added, chosen first as "the
highest-value addition given this project handles real credentials/
tokens," per the issue's own recommended prioritization. Every push/PR
now generates a CycloneDX SBOM (`cyclonedx-maven-plugin`, bound to the
`package` phase) and scans it with Trivy, failing the build on any
HIGH/CRITICAL vulnerability anywhere in the current dependency tree —
a full baseline scan, not merely a gate on what a PR newly adds.

Three approaches were tried and two abandoned, each for a real, verified
reason rather than a guess: `actions/dependency-review-action` (GitHub's
own first-party action) hard-fails on this private repo without GitHub
Advanced Security — confirmed directly against this repo's own CI
("Dependency review is not supported on this repository"), not assumed
from documentation. Pointing a scanner directly at `pom.xml` triggers
live re-resolution of the whole dependency tree over the network,
confirmed to hit a real Maven Central `429 Too Many Requests`. Pointing
one at the packaged fat jar directly found zero scannable files for
reasons not chased down further. The SBOM approach needs neither GHAS
nor an external API key/account (unlike OWASP dependency-check's NVD key
requirement) — Maven itself already resolved the tree by the time the
SBOM is generated, so nothing needs re-resolving over the network.

The first real run against this project's own dependency tree found 3
CRITICAL CVEs (CVE-2026-65182, CVE-2026-65905, CVE-2026-68525) in a
transitive `tomcat-embed-core:11.0.24` (pulled in only for the opt-in,
off-by-default HTTP transport prototype, ADR 0037 — not reachable via
the default stdio-only configuration, though the gate correctly doesn't
know or care about that distinction). Fixed in the same change: an
explicit `tomcat.version` property override to `11.0.25` in `pom.xml`,
all three resolved, confirmed by re-scanning the regenerated SBOM (0
vulnerabilities). GitHub's separate Dependabot security alerts feature
is a further, complementary continuous-monitoring layer — a repo
Settings toggle, not a CI step, so enabling it is a manual step for the
human project owner outside what a CI workflow change can do.

**Follow-up (2026-09-07):** the human project owner enabled that manual
step directly at
the repository's Settings -> Code security page —
Dependency graph, Automatic dependency submission, Dependabot alerts,
and Dependabot malware alerts are all now on, giving continuous
repo-wide coverage alongside the CI-time SBOM scan. Dependabot security
updates (and its dependent, Grouped security updates) were left off
deliberately — they'd auto-open fix PRs directly against `main`
(`dependabot.yml` sets no `target-branch`), bypassing this project's
feature-branch → `devel` → `main` review workflow. Full writeup in
`docs/security/threat-model.md`'s new "Dependency-vulnerability
monitoring" section.

Maven Enforcer, Spotless/Checkstyle, and SpotBugs/PMD remain open,
tracked as the same issue's remaining scope — this remediation
intentionally does not close #42 as fully done, only as "the prioritized
first gate landed."

**Remediation, completed (2026-09-08, ADR 0043):** the remaining three
gates built. Enforcer (`requireJavaVersion`/`requireMavenVersion`/
`banDuplicatePomDependencyVersions`/`requireUpperBoundDeps`, all passing
cleanly on the first run). Spotless (import order, unused imports,
whitespace/newline hygiene — deliberately not a full AST reformatter,
see ADR 0043's Decision drivers for why; found and fixed 6 files with
genuine import-order violations after a real, previously-latent local
`.gitattributes`/line-ending staleness was investigated and resolved).
SpotBugs (`effort=Max`/`threshold=Medium`; 16 findings triaged
individually — 8 genuinely fixed by making `QueryResult`/
`ProcedureDetail`/`ExplainPlanResult` actually immutable
(`List.copyOf` in each compact constructor), the other 8 confirmed as
either a well-known dependency-injection false-positive pattern or an
already-intentional design decision before being excluded, each with a
specific justification in `spotbugs-exclude.xml`). Issue #42 (P1-5) is
now fully closed — all four originally-named gates exist.

## P1-6 — README and security documentation are stale relative to the code

**Classification: Confirmed**, with one sub-point **Rejected with
evidence**.

Confirmed with direct evidence:
- README.md:27,191,221 claims "ten" tools; the codebase has 11 `@Tool`
  methods (grep-confirmed) — `explainQuery` (added per ADR 0033) is
  missing from the count.
- README.md:251 claims "29 ADRs"; `docs/adr/` contains 37
  (0001-0037, `ls`-confirmed). `git log -1 --format=%ad -- README.md` →
  Aug 17, 2026; ADR 0034 → Aug 27; ADR 0037 → Aug 29 — 8 ADRs postdate
  README's last edit.
- README.md:266 claims "[n]o dedicated query-complexity analyzer" — flatly
  contradicted by `SqlComplexityValidator.java` (354 lines, walks the full
  JSqlParser AST, rejects cross/tautological joins, caps join count and
  nesting depth) and ADR 0034 (Accepted, 2026-08-26).
  `docs/security/adversarial-test-cases.md:202-212` independently records
  this same limitation as struck through and closed by ADR 0034 — i.e. a
  second doc in the same repo already reflects the fix; README does not.
- `docs/security/adversarial-test-cases.md:213-222` still describes the
  `EXPLAIN_TIME` correlation race as open, but `docs/adr/0033-explain-plan-acquisition.md`'s
  Status section records it fixed and live-DB2-verified (2026-08-30), and
  the code (`Db2QueryAdapter.java:386-401`) uses a `QUERYTAG`-based join,
  not `MAX(EXPLAIN_TIME)`. `git log` confirms the doc (Aug 29) predates the
  fix (Aug 30) and was never updated afterward.

**Rejected with evidence:** the review's claim that README's "never raw
SQL" traceability wording is "too broad" does not hold up against the
actual text. README.md:46-51 scopes that claim to correlation
IDs/audit/metrics/spans, and README.md:191-193 correctly scopes the
correlation-ID claim to "every structured error" — it does not claim
success responses carry one, and does not claim `explainQuery`'s
`statementText` is redacted. The README does not overclaim on this
specific point; the underlying gap (raw SQL in `ExplainPlanResult`, per
P1-4) is real, but README's own wording does not misstate it.

**Remediation (2026-09-03, issue #43):** Fixed. README's tool count now
reads "Eleven typed MCP tools" (previously "ten"); its ADR count is kept
current (this document's own edits keep it reconciled, most recently to
41); it now states "A dedicated query-complexity analyzer exists
(`SqlComplexityValidator`, ...)" instead of denying one exists; and
`docs/security/adversarial-test-cases.md`'s `EXPLAIN_TIME` correlation
race item is struck through, matching ADR 0033's own fixed status. The
**Rejected with evidence** sub-point (README's "never raw SQL" wording)
was never a gap to begin with, so nothing there needed changing.

## P1-7 — Live-Db2 evidence remains incomplete for core catalog behavior

**Classification: Confirmed**, with material mitigating context the
review's framing does not fully convey.

The narrow technical claim holds: exactly one `*IT.java` file exists
(`Db2LiveConnectivityIT.java`), a single self-skipping connectivity/basic-
catalog-read test — there is no broader automated live-Db2 IT suite.

However, `docs/demo/guided-scenarios.md`'s checklist (lines 62-231) shows
6 of 7 items already checked off with dated, concrete, manually-run
live-Db2 results as of the review date: procedure/overload identity
(CR-003), FK-via-unique-constraint resolution (CR-004), the three demo
scenarios, `getCapabilities`, metrics/span export, and all four
`explainQuery` sub-items (2026-08-30) plus Dynatrace export (2026-09-01).
Only real third-party MCP HTTP client interop remains genuinely
unchecked, and the document itself frames that specifically as an HTTP
interop gap, not a live-Db2 gap. So: the specific requirement this
finding calls for — an *automated* suite, not ad hoc manual verification —
is genuinely missing and should be built before public promotion, but the
review's framing ("live-Db2 evidence remains incomplete... not sufficient
evidence for all metadata queries, overloads, foreign-key resolution,
procedure source...") understates how much of that specific list has, in
fact, already been manually verified against a real DB2 instance with
dated evidence on record.

No remediation is included in this change.

**Remediation (2026-09-09, issue #44, ADR 0044):** Fixed — the human
project owner chose to build this rather than defer it (contrast with
P2-1/#45 below, the one finding from this same review actually
descoped). New `Db2LiveCatalogIT` automates exactly the three specific
claims this finding's own text named as manually-verified-only:
CR-003 (procedure/overload identity), CR-004 (FK-via-unique-constraint
resolution), and live `getCapabilities`/`productVersion`. Run against
the real `db2-mcp-local` instance every prior live-verification session
in this project has used: 5/5 tests passing, 0 failures, on the first
attempt. New `.github/workflows/live-db2-it.yml` (`workflow_dispatch`
only — a throwaway DB2 container is too heavy for every push/PR)
automates the provisioning steps `docs/demo/live-db2-test-prerequisites.md`'s
manual runbook already documented, then runs the suite against it. See
ADR 0044 for the full design and why a GitHub Actions `services:` block
couldn't be used (DB2 requires `--privileged`, which that block doesn't
support).

## P2-1 — Column-level policy is not implemented

**Classification: Confirmed.**

Grep confirms no denylist/redaction/classification/PII construct anywhere
in `src/main/java`. The review's own text notes this "is not necessarily
required for the first minimal portfolio release if explicitly
descoped" — no such descoping decision is currently on record anywhere in
this repository, so the gap stands as newly surfaced and undecided
(Confirmed), not yet Deferred; whether to descope it is a human step-7
decision this document does not make.

No remediation is included in this change.

**Decision (2026-09-07, issue #45 — the human step-7 decision this
document withheld above):** Explicitly descoped for the current phase.
Column-level data classification remains genuinely unimplemented — not
newly fixed, not silently dropped, a deliberate choice now on record.
Redaction continues to happen only at the schema/table boundary
(`SchemaAllowlistValidator`, ADR 0008), same as before this decision.
If revisited later, the human project owner's stated preference is
**redact matching cells, not reject the whole query** — a query
selecting a denylisted column would still run, with those cells masked
in the response, rather than failing closed the way
`SchemaAllowlistValidator`/`SqlStructuralValidator` rejections do today.
Recorded here so a future revisit starts from a decision already made,
not from scratch.

## P2-2 — Cancellation is not a complete feature

**Classification: Confirmed.**

Zero `.cancel(` calls anywhere in `src/main/java` (grep-confirmed). The
only cancellation-adjacent mechanism is `setQueryTimeout`
(`Db2QueryAdapter.java:101`, `Db2MetadataAdapter.java:214`), which relies
entirely on driver-enforced timeout, not an explicit
server-initiated `Statement.cancel()`. No dedicated cancellation
metric/audit-decision value exists. As with P2-1, no existing project
decision records this as deliberately deferred, so it is Confirmed rather
than Deferred pending the human owner's decision.

No remediation is included in this change.

**Remediation (2026-09-07, ADR 0042, issue #46):** Fixed. The human
project owner chose to implement, scoped specifically as
disconnect-triggered cancellation: when the MCP client disconnects while
a query is still running, `Db2QueryAdapter.executeQuery`'s live
`Statement` (now captured via a `StatementCallback`, not the previous
`ResultSetExtractor`) gets proactively cancelled once
`InFlightRequestTracker.awaitDrain`'s existing grace period expires -
gated on that same timeout deliberately, so a query that would have
finished and had its response delivered within the grace period is
never touched, preserving ADR 0031's own fix. New
`ActiveStatementRegistry` (`configuration` package) tracks in-flight
statements and distinguishes an explicit cancellation from a
coincidentally-identical driver timeout (both produce SQLSTATE `57014`)
via a tracked flag, not the SQLSTATE. New `PolicyDecisionReason.CANCELLED`
records the distinction in the audit trail; the client-facing
`ErrorCategory.QUERY_TIMEOUT` needed no new value since the client is,
by definition, already disconnected by the time cancellation fires.
Scoped to `Db2QueryAdapter.executeQuery` only - `Db2MetadataAdapter`/
`lookupRefData`/`explainQuery` are bounded, fast, catalog-only
operations in practice, explicitly left out of scope, see ADR 0042.
Verified end to end against a real H2 connection (not a mocked
`Statement`): a deliberately slow query, configured with a 120s timeout
so a pass can only mean real cancellation, cancelled in ~131ms with the
audit log correctly recording `decision=CANCELLED`.

## P2-3 — HTTP transport remains correctly private-only

**Classification: Accepted risk** for four of the five named gaps;
**Confirmed** for the fifth (documentation completeness only).

TLS, failed-auth rate limiting, per-client identity/authorization, and
HTTP-session shutdown-drain guarantee are each explicitly named as known,
unaddressed gaps already on record in both
`docs/adr/0037-http-transport-and-authentication-prototype.md`
(lines 283-308) and `docs/security/threat-model.md` (rows H2, H4, H5, H6).
ADR 0037's own Status is "Proposed (non-promotable pending security
review)" — i.e. this project has already deliberately accepted these four
gaps for the current private, opt-in prototype phase, consistent with the
review's own conclusion that they "do not block the stdio public subset."
That is a pre-existing, documented risk acceptance, not a newly surfaced
undecided gap — **Accepted risk**.

Token rotation is different: it is a real gap (only a single static
bearer token exists, per ADR 0037's design), but unlike the other four it
is not named explicitly as a gap anywhere in `docs/adr/0037-...md` or
`docs/security/threat-model.md` (grep for "rotat" returns zero hits in
either file) — **Confirmed** as a documentation-completeness gap: the
underlying limitation is implied by the design but should be named
explicitly alongside the other four.

No remediation is included in this change.

**Remediation (2026-09-03, issue #47 — documentation-only, no code
change):** Fixed. Added a new "Bearer token rotation" bullet to ADR
0037's Explicitly out of scope section and a new H7 row to
`docs/security/threat-model.md`'s HTTP transport threats table (plus its
Residual risk summary line), each named explicitly alongside the other
four accepted-risk gaps, in the same style and detail level. The four
already-accepted-risk gaps (TLS, rate limiting, per-client
identity/authorization, shutdown-drain) were left untouched — this
change only closes the documentation-completeness gap, not any of the
underlying accepted risks.

---

## Summary

| Finding | Classification | Remediation |
|---|---|---|
| P0-1 | Confirmed | Fixed (ADR 0039) |
| P0-2 | Confirmed | Fixed (ADR 0039) |
| P0-3 | Confirmed | Fixed (ADR 0039) |
| P1-1 | Confirmed | Fixed (ADR 0039, byproduct of P0-2) |
| P1-2 | Confirmed | Fixed (ADR 0040) |
| P1-3 | Confirmed | Partially fixed (ADR 0041) |
| P1-4 | Confirmed | Fixed (#41) |
| P1-5 | Confirmed (tagged-release sub-item: Deferred) | Fixed (#42, ADR 0043) |
| P1-6 | Confirmed ("never raw SQL" sub-claim: Rejected with evidence) | Fixed (#43) |
| P1-7 | Confirmed (with mitigating context — see above) | Fixed (#44, ADR 0044) |
| P2-1 | Confirmed | Descoped (2026-09-07, #45) |
| P2-2 | Confirmed | Fixed (ADR 0042, #46) |
| P2-3 | Accepted risk (TLS/rate-limit/per-client-auth/drain); Confirmed (token-rotation documentation gap) | Partially fixed (#47) |

12 of 13 findings confirmed as accurate against the current branch; one
sub-claim within P1-6 rejected with evidence; two findings (P1-5's
tagged-release item, P2-3's four core gaps) already reflect a
pre-existing, documented acceptance rather than a newly surfaced,
undecided gap. Per ADR 0038 and the explicit scope of the original
validation change, no remediation was included there — that document was
validation only. Several findings have since been fixed in separate
changes — see ADR 0039 (P0-1, P0-2, P0-3, and P1-1 as a direct byproduct
of P0-2's `lookupRefData` remediation), ADR 0040 (P1-2), ADR 0041 (P1-3,
partially), issue #41 (P1-4, fully), ADR 0043 (P1-5, fully — issue #42's
dependency-scan gate plus Enforcer/Spotless/SpotBugs), issue #43 (P1-6,
fully), ADR 0044 (P1-7, fully), ADR 0042 (P2-2, fully), and issue #47
(P2-3's token-rotation documentation gap, partially) — see each
finding's own "Remediation"/"Decision" note above. P2-1 was explicitly
descoped by the human project owner (2026-09-07, issue #45) rather than
fixed — a deliberate decision, not an oversight, with a recorded
preference (redact, not reject) for if it's revisited later — the one
finding from this review actually deferred; every other Confirmed
finding was either fixed or partially fixed. P1-3/P2-3 remain partially
open, for the human project owner's step-7 acceptance/prioritization
decision.
