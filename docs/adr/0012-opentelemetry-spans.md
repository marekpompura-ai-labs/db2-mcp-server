# ADR 0012: OpenTelemetry spans for the executeQuery pipeline

## Status
Accepted — implemented and verified in this environment (`mvn test`
green, 90 tests; real Spring context startup and no-network-exposure
both re-confirmed against the packaged jar).

## Date
Proposed 2026-08-13, Accepted 2026-08-13.

## Context
ADR 0010 (audit logging) and ADR 0011 (Micrometer metrics) both cover
`executeQuery`'s outcome — what happened, how long it took in total.
Neither shows *where* time went across the guard chain (capability
check → structural validation → schema allowlist → DB2 execution), or
gives a caller-side view of causality across a request. Master brief
§9.3 specifies a concrete span hierarchy for this:

```
mcp.tool <tool-name>
├── input.validate
├── capability.authorize
├── sql.validate
├── scope.authorize
├── db2.execute
├── result.redact
└── response.serialize
```

with span attributes required to be allowlisted and sanitized (no raw
SQL, no row data).

No OpenTelemetry collector is reachable from this environment — ADR
0009's environment audit already confirmed no Docker daemon and no
raw-TCP network access, which rules out actually shipping spans to a
live backend (Jaeger, Zipkin, an OTLP collector) here. Brief §9.1 marks
Grafana/Prometheus/Dynatrace examples as explicitly optional, but still
names OpenTelemetry as a required technology alongside Micrometer for
metrics (§9.1) — not a substitute for it.

## Decision drivers
- Must produce genuine OpenTelemetry spans (real trace/span IDs,
  parent-child structure), not a hand-rolled imitation — this project's
  established preference for proven libraries over homemade substitutes
  for well-solved problems (same reasoning as choosing JSqlParser over
  a hand-written SQL tokenizer, ADR 0007).
- Must not require a live collector to be useful or testable in this
  environment, since none exists here.
- Must stay swappable for a real backend later without a rewrite —
  changing the exporter should be a dependency/config change, not new
  application code.
- Must not touch ADR 0011's already-Accepted `MetricsRecorder` — this is
  a separate concern (tracing, not metrics), following the same
  separation ADR 0010 (audit) and ADR 0011 (metrics) already established
  rather than merging everything into one shared instrumentation layer.
- Attributes must come from the same closed, safe value set already
  used for audit/metrics (tool name, `PolicyDecisionReason`, SQLSTATE) —
  never raw SQL or row data.

## Considered options
1. **`micrometer-tracing-bridge-otel` + `opentelemetry-exporter-logging`.**
   Real OpenTelemetry SDK underneath (via Micrometer Tracing's bridge),
   spans written as structured log lines when they complete instead of
   shipped over the network. Genuinely inspectable as portfolio
   evidence, zero network exposure, and swapping in a real OTLP exporter
   later is a dependency + configuration change, not an application
   rewrite, since the instrumentation code talks to Micrometer
   Tracing's `Tracer`/`Span` API, not the exporter directly.
2. **A real OTLP exporter pointed at a collector.** Rejected for this
   environment: no collector is reachable (ADR 0009), so spans would
   either fail to export or block/retry against nothing — not
   verifiable here, and not honest to claim as "working."
3. **Micrometer's `Observation` API instead of `Tracer`/`Span` directly.**
   `Observation` is the more modern, unified Spring Boot 3 abstraction
   (one recording produces both a metric and a span when both handler
   types are registered). Considered, but ADR 0011's `MetricsRecorder`
   already exists as direct Micrometer `Counter`/`Timer`/
   `DistributionSummary` calls, not `Observation`-based — adopting
   `Observation` now would mean either two parallel instrumentation
   styles or rewriting already-Accepted, already-tested ADR 0011 code.
   Deferred: worth considering if/when ADR 0011 is revisited, not forced
   into this ADR's scope.
4. **Hand-rolled span-shaped logging** (start/end timestamps and nesting
   printed as plain log lines, no OTel SDK at all). Rejected: doesn't
   produce genuine OpenTelemetry spans, contradicting the brief's
   explicit technology choice and this project's stated preference for
   real libraries over imitations.

## Decision
Add `micrometer-tracing-bridge-otel` and `opentelemetry-exporter-logging`.
Build spans manually via Micrometer Tracing's `Tracer`/`Span` API (not
`Observation`, per decision driver above) inside `Db2QueryAdapter`,
matching brief §9.3's hierarchy: a root `mcp.tool executeQuery` span,
with child spans for each guard stage and `db2.execute` for the JDBC
call. Span attributes limited to: tool name, `PolicyDecisionReason`,
capability profile, SQLSTATE (on failure) — the exact same safe value
set already used for ADR 0010's audit events and ADR 0011's metrics,
reused rather than inventing a new attribute vocabulary.

## Consequences
- **Positive, and genuinely exercised:** `Db2QueryAdapter.executeQuery`
  now produces a real span tree — root `mcp.tool executeQuery` plus
  child spans for `capability.authorize`, `input.validate`,
  `sql.validate`, `scope.authorize`, and `db2.execute` — verified with
  `SimpleTracer`, including that a schema-allowlist rejection correctly
  produces a *partial* tree (`db2.execute` never starts) and that every
  span in a call shares one trace ID.
- **Dependency resolution confirmed clean, not assumed:** all of
  `micrometer-tracing-bridge-otel`, `opentelemetry-exporter-logging`,
  and `micrometer-tracing-test` resolved through Spring Boot 3.4.5's
  managed versions (`micrometer-tracing:1.4.5`, `opentelemetry-api:1.43.0`,
  and the rest of the OTel SDK) with no explicit pins needed and no
  conflicts — checked via `mvn dependency:resolve`, not inferred from
  Spring Boot's documentation.
- **Real finding: `SimpleTracer.getSpans()` returns spans in *start*
  order, not *end* order.** The first version of the span-order test
  assumed the reverse — that the root span (whose `.end()` call happens
  last, in the `finally` block) would appear last in the list. It
  appears *first*, since it's the first span *started*. The test's
  expectation was wrong, not the instrumentation; fixed once the actual
  order was observed rather than assumed. Same "verify, don't assume
  framework behavior" pattern as ADR 0007's `parseStatements` discovery
  and ADR 0010's logback-in-tests finding.
- **No-network-exposure claim re-confirmed**, not just carried over from
  ADR 0011: the packaged jar was started again with the new tracing
  dependencies present and inspected with `lsof` — still zero TCP/UDP/
  LISTEN file descriptors.
- **Known limitation, as anticipated:** live, end-to-end span export
  through an actual MCP client call was not exercised — there's no way
  to drive a real stdio MCP handshake in this session. What *was*
  verified: the real Spring context starts cleanly with the tracer
  autoconfiguration active (`Started Db2McpServerApplication` succeeds,
  no errors, 9 tools still register), and `SimpleTracer`-based tests
  confirm the instrumentation itself is correct. Whether the
  `LoggingSpanExporter` actually writes readable span output when a
  real query runs through the full stdio protocol remains unverified —
  stated here, not implied away.
- **`result.redact` and `response.serialize` spans from brief §9.3's
  suggested hierarchy were not implemented** — no redaction logic
  exists in this adapter, and response serialization happens inside the
  Spring AI MCP framework, outside this class's control. Fabricating
  empty spans for stages that don't do real work here was rejected as
  dishonest instrumentation; the hierarchy implemented reflects what
  this adapter actually does.

## Verification / enforcement
`mvn test` (90 tests total) includes three new `Db2QueryAdapterIntegrationTest`
cases using a real `SimpleTracer`: full span tree on success (six spans,
verified start order), partial tree on schema rejection (`db2.execute`
absent, proven by the span list itself, not just the thrown exception),
and a single shared trace ID across every span in one call. Separately,
the packaged jar was started for real to confirm clean startup with the
tracer autoconfiguration and re-check the no-network-port claim with
`lsof`.

## Related decisions
ADR 0001 (no network exposure), ADR 0010 (audit — the same
`PolicyDecisionReason`/attribute vocabulary reused here), ADR 0011
(metrics — deliberately kept separate, not merged).
