# Phase 5 Private-Research Roadmap

## Source

This roadmap sequences the seven private-research threads listed in
`docs/master-brief.md:1163-1173` ("Phase 5 — Private research"):

> - Explain-plan acquisition and analysis.
> - Query complexity/cost controls.
> - Advanced query policy.
> - HTTP transport and authentication prototype.
> - Multi-profile/multi-database experiments.
> - Dynatrace-specific integration.
> - Performance and red-team testing.
>
> Only promote a private experiment into the public repository when it has a
> clear use case, stable contract, security story, tests, documentation, and
> no confidential provenance.

If this list or its wording changes in the master brief, update this file to
match — this roadmap is a sequencing plan derived from that source, not an
independent authority.

## How this works

One thread at a time, in the order below, with a check-in before moving to
the next. Most threads become their own numbered ADR under `docs/adr/`,
following this project's existing ADR-per-decision practice; two exceptions
are noted below. Nothing here is committed or promoted to a public-facing
narrative until it independently meets the brief's own promotion bar (clear
use case, stable contract, security story, tests, documentation, no
confidential provenance).

## Sequence

### 1. Advanced query policy (foundation)

`CapabilityProfile.ADVANCED_QUERY` (`src/main/java/io/github/marekpompura/db2mcp/policy/capability/CapabilityProfile.java`)
already exists as an enum value but has no differentiated behavior today —
it's treated identically to `SAFE_QUERY` everywhere it's checked
(`McpConfig.schemaToolCallbackProvider`, `Db2QueryAdapter.requireQueryExecutionAllowed`).
The master brief's own description of this profile (§6.3, lines 284-289)
frames it as "private experimentation only by default... may support
explain-plan experiments or configurable policy exceptions... must never
weaken database-account least privilege." This thread defines what
`ADVANCED_QUERY` actually unlocks, so threads 2 and 3 have somewhere real to
attach. Own ADR.

### 2. Explain-plan acquisition and analysis

DB2's JCC driver (`com.ibm.db2:jcc:11.5.9.0`, pinned per ADR 0003) supports
`EXPLAIN PLAN FOR <stmt>` and reading `EXPLAIN_STATEMENT`/`EXPLAIN_OPERATOR`
catalog tables through ordinary JDBC — no special driver feature needed,
just additional SQL issued through the existing `Db2QueryAdapter`/
`JdbcTemplate` (`src/main/java/io/github/marekpompura/db2mcp/adapter/db2/Db2QueryAdapter.java`).
Gated behind the real `ADVANCED_QUERY` semantics from thread 1. Needs DB2
EXPLAIN tables provisioned in the target schema plus grants — a live-DB2
verification step, same pattern as ADR 0022. Own ADR.

### 3. Query complexity/cost controls

No complexity scoring exists today — `SqlStructuralValidator` and
`SchemaAllowlistValidator` (`src/main/java/io/github/marekpompura/db2mcp/policy/sql/`)
check statement type and schema membership, not join count or nesting depth.
The brief is explicit this is conditional: "consider query-cost controls
only if they are deterministic enough to explain and test" (line 327). Can
run alongside or just after thread 2. Own ADR.

### 4. Performance and red-team testing

No load or adversarial test infrastructure exists yet (`scripts/` has only
`smoke-test.sh` and `eof-shutdown-race-test.sh`; no JMH/Gatling in the repo).
Sequenced after threads 1-3 so the red-team suite covers the new
explain-plan and cost-control surfaces, not just what's already shipped.
Existing test shapes to extend: `CapabilityProfileTest`,
`SqlStructuralValidatorTest`, `SchemaAllowlistValidatorTest`
(`src/test/java/io/github/marekpompura/db2mcp/policy/`).

Originally scoped as "no new ADR expected — test-only work." That turned
out to be wrong: researching this thread's own two stated concerns (a
concurrency test, and red-teaming the new explain-plan/complexity-control
surfaces) surfaced three real implementation gaps — an unimplemented
master brief §7.5 requirement (bound concurrent tool executions), and two
SQL-policy bypasses in `SqlComplexityValidator`/`SqlStructuralValidator`.
Given each directly, the decision was to fix all three, not just document
them — see ADR 0035.

### 5. Dynatrace-specific integration

Today's observability export (`ObservabilityExportConfig`, ADR 0030) is
stderr-only: `LoggingSpanExporter` and a Micrometer `LoggingMeterRegistry`,
no OTLP exporter configured (`pom.xml` only pulls
`opentelemetry-exporter-logging`). A real Dynatrace integration means a live
tenant endpoint and token, which is exactly the "confidential provenance"
risk the brief warns about — must stay config-driven via environment
variables (matching ADR 0004's existing credential pattern), with no real
endpoint or token ever committed. Own ADR.

### 6. HTTP transport and authentication prototype

The largest architectural departure. ADR 0001 already decided stdio-only,
explicitly rejecting HTTP for its no-network-exposure security story. The
MCP SDK version pinned here (`io.modelcontextprotocol.sdk:mcp-core:2.0.0`,
via `spring-ai-starter-mcp-server:2.0.0`) ships
`HttpServletStreamableServerTransportProvider` (the modern Streamable HTTP
transport) directly — no SDK upgrade needed (this section previously said
otherwise, based on a stale `mcp:0.10.0` pin; corrected once the real
pinned version was confirmed directly against the resolved dependency
tree). There is also currently no MCP-layer client-auth concept at all
(stdio's trust model is "the spawning process is the caller"), so this
thread has to design authentication from scratch, not just add a
transport. Own ADR, originally marked non-promotable until a dedicated
security review closed. **Superseded 2026-09-21: that review is complete
(`docs/security/http-transport-security-review-2026-09-21.md`) and ADR 0037
is Accepted within an experimental prototype boundary.**

### 7. Multi-profile/multi-database experiments — private-only, isolated

Per explicit direction: this conflicts with the project's identity as
"Governed Db2 MCP Server," a DB2-specific tool, so it stays private and
isolated — never merged to `main`, never referenced in public-facing docs
beyond this roadmap entry. Confirmed today: exactly one `DataSource`, no
multi-connection concept. `DatabaseSelectionProperties`
(`src/main/java/io/github/marekpompura/db2mcp/adapter/db2/DatabaseSelectionProperties.java`)
already has a `DatabaseProduct product` seam (ADR 0021/0028) intended for
exactly this kind of extension, so the seam exists if this is ever pursued.
Sequenced last: lowest priority, most divergent from the project's DB2-
specific positioning, pursued only to close out the brief's own list. **No
ADR** — informal notes only, on a dedicated non-`main` branch, unless
circumstances change enough to seriously reconsider promoting it (which
would itself need a fresh conversation with the user first, given the
identity conflict already flagged).

## Status

- **Thread 1 (advanced query policy): done.** `ADVANCED_QUERY` now has real,
  differentiated query limits (own `maxRows`/`timeoutSeconds`/`maxCellBytes`/
  `maxPayloadBytes`, `db2mcp.advanced-query.*`), genuinely enforced by
  `Db2QueryAdapter` and genuinely reported by `getCapabilities`. See ADR
  0032.
- **Thread 2 (explain-plan acquisition and analysis): implemented,
  pending live-DB2 verification.** New `explainQuery` MCP tool
  (`ADVANCED_QUERY`-only), backed by a new `ExplainPort` deliberately kept
  separate from `QueryPort` (DB2-only, no H2/fake-adapter equivalent, per
  ADR 0009). `getCapabilities.capabilities.explainPlan` now `true` under
  `ADVANCED_QUERY`. See ADR 0033 - status was `Proposed`, not `Accepted`, until
  the live-DB2 checklist item in `docs/demo/guided-scenarios.md` had been
  run (**superseded: that item was run and ADR 0033 is now Accepted**);
  the EXPLAIN-table correlation strategy in particular is flagged as the
  part most likely to need rework, not just documentation, once verified.
- **Thread 3 (query complexity/cost controls): done.** New
  `SqlComplexityValidator` walks the whole statement tree (not just the
  top-level FROM clause) and rejects cross joins unconditionally, caps
  join count and subquery nesting depth per profile
  (`db2mcp.query-complexity.*`/`db2mcp.advanced-query-complexity.*`),
  wired into both `executeQuery` and `explainQuery`. `getCapabilities`
  now reports `limits.maxJoins`/`limits.maxNestingDepth`. Unlike thread 2,
  this needed no live-DB2 verification — it's a client-side, deterministic
  structural check that runs entirely before any SQL reaches DB2, so
  H2/unit coverage plus a manual packaged-jar run (against a deliberately
  unreachable DB2 host, so a validation rejection is distinguishable from
  a downstream DB2 failure by error code alone) was a complete
  verification. See ADR 0034 — status `Accepted`.
- **Thread 4 (performance and red-team testing): done.** New
  `HikariPoolConcurrencyIntegrationTest` proves what actually happens under
  pool contention (queuing bounded by `connection-timeout`, or a sanitized
  connection-acquisition failure). Red-teaming the new explain-plan/
  complexity-control surfaces surfaced three real gaps, all closed:
  `SqlComplexityValidator`'s tautological-`ON` bypass (`JOIN ... ON 1=1`),
  a piped-SQL bypass of structural/schema/complexity validation
  simultaneously, and master brief §7.5's "bound concurrent tool
  executions," previously declared but never implemented — now enforced by
  a new `ToolExecutionAdmissionGate`/`AdmissionControlledToolCallback`
  wrapping every registered tool (`db2mcp.concurrency.
  max-concurrent-tool-executions`). Scope grew beyond "test-only" once
  these were found, so — contrary to this thread's original framing above —
  it did get its own ADR. See ADR 0035 — status `Accepted`. Full suite now
  287 tests.
- **Thread 5 (Dynatrace-specific integration): implemented, pending
  live-Dynatrace-tenant verification.** New `DynatraceExportProperties`/
  `DynatraceExportPropertiesValidator` (opt-in, `db2mcp.dynatrace.enabled`
  defaults `false`, fail-fast if enabled without a valid endpoint/token)
  and `DynatraceObservabilityConfig` (`configuration`), registering a real
  `SpanProcessor` (OTLP HTTP, `opentelemetry-exporter-otlp`) and a real
  `MeterRegistry` (Micrometer-native `DynatraceMeterRegistry`,
  `micrometer-registry-dynatrace`, Dynatrace's Metrics API v2 — chosen
  over the generic OTLP metrics exporter specifically to avoid its
  delta-temporality requirement) additively alongside ADR 0030's existing
  stderr-only logging exporters, both `@ConditionalOnProperty`-gated so
  disabled means zero Dynatrace beans exist at all. `EffectiveConfigReporter`
  deliberately never wired to the token (exclusion by construction, same
  standard as DB2 credentials). Verified with a local fake HTTP server
  standing in for Dynatrace's ingest endpoints (correct paths, exact
  `"Api-Token "` header format, real span/metric data reaching request
  bodies, and a dedicated leak-detection test confirmed to genuinely catch
  a leak) plus a `@SpringBootTest` proving real Spring wiring end-to-end.
  See ADR 0036 — status was `Proposed`, not `Accepted`, until the
  real-tenant checklist item in `docs/demo/guided-scenarios.md` had been
  run (**superseded: it was run against a real Dynatrace trial tenant and
  ADR 0036 is now Accepted**); the actual
  Dynatrace ingestion/token-validation behavior is the part flagged as
  genuinely unverifiable from this environment. Full suite now 307 tests.
- **Thread 6 (HTTP transport and authentication prototype): implemented,
  fully verified locally; the dedicated security review is complete and
  ADR 0037 is Accepted within an experimental prototype boundary
  (2026-09-21).** New opt-in HTTP transport
  (`db2mcp.http.enabled`, defaults `false`) hand-wired from the MCP SDK's
  own `HttpServletStreamableServerTransportProvider`
  (`spring-boot-starter-tomcat` + `spring-web`, deliberately not
  `spring-ai-starter-mcp-server-webmvc` or the full `spring-boot-starter-web`),
  gated by a real, confirmed constraint: this MCP SDK/Spring AI version
  wires exactly one active transport-provider bean, so `StdioLifecycleConfig`'s
  stdio bean is now itself `@ConditionalOnProperty`-gated off whenever HTTP
  is enabled — a genuine XOR between two server modes, not simultaneous
  multi-transport. Authentication is a single static bearer token
  (`db2mcp.http.bearer-token`, env-var-supplied, ADR 0004's pattern),
  checked by a plain servlet `Filter` before any MCP/tool logic runs — not
  OAuth 2.1 (explicitly scoped out as too large for a first prototype) and
  not per-client capability profiles (`CapabilityProfile` stays the single
  process-wide value it already is). Verified with a real embedded Tomcat
  on a random port and a real MCP SDK client (`HttpClientStreamableHttpTransport`)
  performing a genuine handshake and tool call — unlike threads 2 and 5,
  nothing here needed a live external system, so this thread is fully
  locally verified, not pending live-infrastructure verification; it
  remained `Proposed` because the roadmap's own gating for this thread
  specifically was a security review, not a missing live system.
  **Superseded 2026-09-21: that review is complete
  (`docs/security/http-transport-security-review-2026-09-21.md`) and ADR
  0037 is Accepted within an experimental prototype boundary.** See ADR
  0037. Full
  suite now 325 tests.
- Thread 7: not started (deliberately deprioritized, private-only, no ADR
  planned — see its own section above).
