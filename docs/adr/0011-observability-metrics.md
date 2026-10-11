# ADR 0011: Observability via Micrometer, with no exposed HTTP/JMX endpoint

## Status
Accepted — implemented and verified in this environment (`mvn test`
green, 87 tests; no-network-port claim independently confirmed against
the real packaged process, not assumed from framework documentation).

## Date
Proposed 2026-08-13, Accepted 2026-08-13.

## Context
Nothing in this project currently measures anything — call counts,
durations, rejection reasons, row volumes all exist only as one-shot
audit log lines (ADR 0010), not as aggregable metrics. Master brief §9.2
lists specific metric names (`mcp.tool.calls`, `mcp.tool.duration`,
`security.policy.decisions`, etc.) and explicitly warns against
unbounded labels (correlation ID, raw SQL, user text).

A real tension exists that the brief itself calls out (§9.4): Spring
Boot's standard way of exposing Micrometer metrics is Actuator's HTTP
endpoints — but this server is stdio-only with **no network exposure**
by design (ADR 0001's stated consequence). Adding metrics must not
silently reopen that.

## Decision drivers
- Must not open any network port. ADR 0001's "no network exposure"
  consequence is a stated security property of this server, not
  incidental — metrics work must not undo it.
- Must avoid unbounded-cardinality labels per brief §9.2 (no correlation
  ID, no raw SQL, no arbitrary user text as a tag value).
- Must be independently verifiable, not just "should work per Spring
  Boot's documented auto-configuration" — this project's standing
  practice is to check claims, not assume framework behavior applies
  correctly to this specific (non-web, stdio) setup.
- Should reuse the guard-stage structure already built for audit
  logging (ADR 0010) rather than duplicating try/catch blocks.

## Considered options
1. **`spring-boot-starter-actuator` without any web starter present.**
   Actuator's HTTP endpoint exposure requires a servlet/reactive web
   application context; this project has neither
   (`spring-boot-starter-web`/`-webflux` were never added, and adding
   one now just for metrics would be a much bigger, unjustified change).
   Without a web context, Actuator still provides the `MeterRegistry`
   infrastructure and auto-configured binders (JVM, HikariCP) with
   nothing exposed over HTTP.
2. **A full observability stack now** (Prometheus registry + exporter,
   Grafana dashboard). Rejected as premature: brief §9.1 explicitly
   marks Grafana/Prometheus/Dynatrace examples as optional, and there is
   no running collector in this environment to export to anyway (no
   Docker daemon, confirmed in ADR 0009's environment audit). A
   `SimpleMeterRegistry` (in-memory, Spring Boot's default when no
   monitoring backend is configured) is sufficient to prove
   instrumentation is real and correctly tagged.
3. **Skip Micrometer, log metric-shaped lines instead of using a real
   registry.** Rejected: defeats the purpose of aggregable metrics
   (counts, percentiles) and brief §9.1 is explicit that Micrometer is
   the intended technology, not an ad hoc substitute.

## Decision
Add `spring-boot-starter-actuator` **without** any web starter. Verify
directly, not assumed, that no network port opens as a result (`ss -tlnp`
against the running process). Explicitly set `spring.jmx.enabled: false`
in `application.yml` too, so local JMX registration doesn't become an
unintended side channel either, even though JMX isn't network-exposed by
default.

Metrics implemented for `executeQuery` (mirrors the audit-logging scope
decision in ADR 0010 — this is the highest-value, richest-decision-surface
tool; the eight metadata/lookup tools are out of scope for this pass):

- `mcp.tool.calls` (Counter) — tags: `tool`, `outcome` (`allowed`/
  `rejected`/`failed`)
- `mcp.tool.duration` (Timer) — tags: `tool`; wraps the entire call
- `mcp.tool.rejected` (Counter) — tags: `tool`, `reason`
  (`PolicyDecisionReason`, same enum as ADR 0010's audit events)
- `mcp.result.rows` (DistributionSummary) — tags: `tool`; recorded only
  on success
- `db2.query.duration` (Timer) — tags: `tool`; wraps only the JDBC
  execution phase, not guard-chain validation
- `db2.errors` (Counter) — tags: `sqlstate` (or `unknown` if unavailable)
- `security.policy.decisions` (Counter) — tags: `profile`
  (`CapabilityProfile`), `decision` (`PolicyDecisionReason`) — every
  call, every outcome, the single metric that answers "what did the
  policy engine actually decide, how often"

All tag values come from closed, small enums (`PolicyDecisionReason`,
`CapabilityProfile`, tool name) or SQLSTATE codes (a bounded standard
code space) — never correlation IDs, raw SQL, or free text, satisfying
brief §9.2's cardinality warning by construction, not by convention.

**HikariCP pool metrics**: Spring Boot auto-binds these when Actuator +
a `HikariDataSource` bean are both present — this should apply
automatically without additional code. **Not independently verified in
this pass** — doing so would require querying a live registry inside
the running process, which isn't possible without exposing an endpoint,
which is exactly what this ADR decided against. Flagged as an accepted,
explicit gap rather than an assumed win.

**OpenTelemetry spans (brief §9.3) are explicitly out of scope for this
ADR** — tracked as separate follow-up work, not attempted here.

## Consequences
- **Positive, and genuinely exercised:** every `executeQuery` outcome
  now produces real metrics — `mcp.tool.calls`, `mcp.tool.duration`,
  `mcp.result.rows`, `db2.query.duration`, `security.policy.decisions`,
  and (on failure) `db2.errors` — verified with a fresh
  `SimpleMeterRegistry` per test, asserting exact counts and tag values
  after calling the adapter through success, schema-rejection, and
  execution-failure paths. Reused ADR 0010's guard-stage try/catch
  structure directly (`recordOutcome` now calls both the audit logger
  and the metrics recorder at each stage) — no duplication, no changes
  to any existing exception behavior.
- **The no-network-port claim was independently confirmed, not just
  argued from documentation.** Started the real packaged jar with
  `spring-boot-starter-actuator` present and checked with `lsof -p
  <pid>`: **zero** TCP/UDP/LISTEN file descriptors out of 30 total open
  FDs. `ss`/`netstat` weren't available in this environment, so `lsof`
  and a direct `/proc/<pid>/fd` inspection were used instead — both
  agreed. This is the same "verify, don't assume framework behavior"
  discipline this project has applied everywhere else (ADR 0003's
  Maven Central check, ADR 0007's JSqlParser API verification).
- **Risk, explicitly accepted:** HikariCP metrics binding is assumed
  correct per Spring Boot's documented auto-configuration behavior, not
  independently confirmed — doing so would require querying a live
  registry inside the running process, which the no-exposed-endpoint
  decision above specifically forecloses. If this matters later, the
  fix is a temporary, deliberately-enabled local diagnostic endpoint,
  not a permanent one.
- **Scope limits, deliberate:** only `executeQuery`, matching ADR 0010.
  Spans/tracing (brief §9.3) are a separate, not-yet-started piece of
  Phase 4.

## Verification / enforcement
`mvn test` (87 tests total) includes three new
`Db2QueryAdapterIntegrationTest` cases using a real `SimpleMeterRegistry`:
metrics on success (call count, duration, row distribution, DB2 query
duration, policy-decision counter all asserted with exact values), on
schema-allowlist rejection (`mcp.tool.rejected` tagged with the correct
reason), and on a real H2 execution failure (`db2.errors` recorded,
`db2.query.duration` still captured even though the query failed).
Separately, the packaged jar was started for real and inspected with
`lsof` to confirm the no-network-port claim holds in practice, not just
in theory.

## Related decisions
ADR 0001 (no network exposure — the constraint this ADR works within),
ADR 0010 (traceability — the guard-stage structure and
`PolicyDecisionReason` enum this reuses).
