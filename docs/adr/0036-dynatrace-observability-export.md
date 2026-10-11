# ADR 0036: Dynatrace-specific observability export (Phase 5 thread 5)

## Status
Accepted — live-verified against a real personal Dynatrace trial tenant (2026-09-01). Metrics export and trace export both confirmed working end-to-end. Trace export was initially found **broken** in real usage - a real shutdown-timing race silently dropped span exports almost every time this server's short-lived process exited, tracked as and fixed by issue #33 (explicit `SdkTracerProvider.forceFlush().join(...)` added to `StdioLifecycleConfig`'s shutdown sequence). Re-verified live after the fix: the `mcp.tool getCapabilities` span from the fixed jar's run landed in the tenant's Distributed Tracing Explorer with no manual workaround. See "Live-Dynatrace verification" below for the full result.

## Date
Proposed 2026-08-28.

## Context
`docs/research/phase5-roadmap.md` sequences this as thread 5, right after
thread 4 (ADR 0035). Today's observability export (`ObservabilityExportConfig`,
ADR 0030) is stderr-only: `LoggingSpanExporter`/`SimpleSpanProcessor` for
traces, `LoggingMeterRegistry` for metrics — no network export exists. ADR
0030 explicitly considered and rejected OTLP export to a local collector,
but for a reason that doesn't apply here: "no such external service is
reachable in this environment" (the same class of constraint as ADR 0009's
no-live-DB2 limitation), not a standing objection to network export in
general. A real Dynatrace tenant is new territory ADR 0030 never ruled out.

Master brief framing is consistent throughout: Dynatrace is explicitly
OPTIONAL and PRIVATE-repo-only — §9.1 ("Optional examples for Grafana/
Prometheus and Dynatrace"), §15.6 Private-version-include ("Internal
Dynatrace configuration and dashboard experiments"), the Phase 5 roadmap
line itself, gated by the line immediately after that list: "Only promote a
private experiment into the public repository when it has a clear use
case, stable contract, security story, tests, documentation, and no
confidential provenance." §15.4 Public-version-exclude explicitly bars
"Credentials, tokens, certificates, connection strings, or secret
locations" and "Company-specific observability endpoints or dashboards
containing identifiers."

**Verification framing**: the same pattern ADR 0033 (thread 2, explain-plan)
already established for "implemented but genuinely needs a live external
system this environment doesn't have" — everything H2/local-fake-server-
testable gets full coverage; this ADR ships `Proposed`, not `Accepted`,
until run against a real tenant.

**Metrics path**: `io.micrometer:micrometer-registry-dynatrace`
(Micrometer-native `DynatraceMeterRegistry`, against Dynatrace's Metrics
API v2), not the generic OTLP metrics exporter — fits this project's
existing `MeterRegistry`-abstraction pattern (just another concrete
registry bean, same shape as `LoggingMeterRegistry`) and avoids OTLP
metrics' delta-temporality requirement entirely by not routing metrics
through OTLP at all. Traces have no such alternative — no Micrometer-native
Dynatrace tracing bridge exists, so traces go through
`opentelemetry-exporter-otlp`'s `OtlpHttpSpanExporter` (Dynatrace's OTLP
endpoint is HTTP-only, no gRPC).

Both new dependencies are already pinned by Spring Boot 4.1.0's own
dependency management (`opentelemetry-exporter-otlp:1.62.0`,
`micrometer-registry-dynatrace:1.17.0`, confirmed directly via `mvn
help:evaluate`), so `pom.xml` entries need no explicit `<version>`,
matching how `opentelemetry-exporter-logging` is declared today. Multiple
simultaneous `SpanProcessor` beans are natively additive in Spring Boot
4.1.0 — confirmed directly from `OpenTelemetryTracingAutoConfiguration`'s
decompiled bytecode: its `spanProcessors(ObjectProvider<SpanProcessor>)`
bean collects **every** `SpanProcessor` bean via `orderedStream().toList()`.

**A real, previously-undiscovered conflict, found during implementation**:
merely adding `micrometer-registry-dynatrace` to the classpath makes Spring
Boot's own built-in `DynatraceMetricsExportAutoConfiguration` try to
auto-register its own `dynatraceMeterRegistry` bean, driven by
`management.dynatrace.*` properties this project never sets — a real
`BeanDefinitionOverrideException` at context startup, colliding by name
with this ADR's own hand-wired bean of the same name. Fixed by explicitly
excluding that autoconfiguration on `Db2McpServerApplication`. This project
already deliberately hand-wires its exporters rather than relying on
Spring Boot's own vendor-registry autoconfiguration (ADR 0030's same choice
for the logging exporters), so excluding Boot's competing autoconfiguration
is consistent with that precedent, not a workaround bolted on.

## Decision drivers
- Master brief §15.4's explicit bar on committing credentials/tokens/
  company-specific endpoints meant the config model had to guarantee "no
  confidential provenance" by construction, not by convention — an explicit
  `enabled` flag (default `false`), never inferring "enabled" from the mere
  presence of an endpoint/token, matching ADR 0028's established "explicit
  config, fail-fast validation" precedent over implicit inference.
- `EffectiveConfigReporter`'s existing standard for credentials is exclusion
  by construction (DB2 username/password are never wired into that
  reporter's dependencies at all, not merely masked) — the same standard
  had to apply to the Dynatrace API token, not a weaker masking approach.
- A caller must not be able to silently misconfigure `environment-url` with
  the wrong API path — `DynatraceExporterV2`'s own code uses its configured
  URI as the complete, literal ingest URL and warns at runtime about
  exactly this common mistake — so the config model exposes one bare-host
  `environment-url`, with each bean-construction site appending its own
  correct, hardcoded path suffix, rather than trusting two independently
  fallible raw endpoint strings.

## Decision
New `DynatraceExportProperties` (`adapter/observability`, prefix
`db2mcp.dynatrace`): `boolean enabled` (default `false`), `String
environmentUrl` (default `""`, bare host only, e.g.
`https://abc12345.live.dynatrace.com`), `String apiToken` (default `""`).

New `DynatraceExportPropertiesValidator implements ApplicationRunner`
(`adapter/observability`), matching `AuditFileAvailabilityValidator`'s
established shape: does nothing when `enabled=false`; when `enabled=true`,
fails startup with a new `DynatraceConfigurationException` if
`environmentUrl`/`apiToken` are blank or `environmentUrl` isn't an absolute
`https://` URL. The exception message interpolates only *property names*
of what's missing, never the token value itself — the one line in this
whole feature that could leak a token into a startup-failure stack trace,
guarded by a dedicated test (`run_exceptionMessage_neverContainsTheApiTokenValue`)
that constructs a config with a real sentinel token value and asserts it
never appears in the thrown message.

New `DynatraceObservabilityConfig` (`configuration`), a sibling of, not a
change to, `ObservabilityExportConfig` — the latter is `Accepted` (ADR
0030), trivially simple, with no conditionals or credential-adjacent logic;
folding in credential-handling, network-configuring, conditionally-
registered beans there would change its risk profile for no benefit. Two
`@Bean` methods, each `@ConditionalOnProperty(prefix = "db2mcp.dynatrace",
name = "enabled", havingValue = "true")` — the first use of this annotation
in this codebase's main code, chosen because the condition is evaluated
*before* the factory method ever runs, giving a genuine bean absence when
disabled (not a bean that behaves as a no-op, which a manual `if` inside an
always-registered `@Bean` method returning `null` cannot cleanly achieve —
Spring's `NullBean` semantics):

```java
@Bean
@ConditionalOnProperty(prefix = "db2mcp.dynatrace", name = "enabled", havingValue = "true")
public SpanProcessor dynatraceSpanProcessor(DynatraceExportProperties properties) {
    SpanExporter exporter = OtlpHttpSpanExporter.builder()
            .setEndpoint(properties.environmentUrl() + "/api/v2/otlp/v1/traces")
            .addHeader("Authorization", "Api-Token " + properties.apiToken())
            .build();
    return SimpleSpanProcessor.create(exporter);
}
```

`SimpleSpanProcessor`, not a bare `SpanExporter` bean auto-wrapped in
Spring's default `BatchSpanProcessor` — the exact same reasoning ADR 0030
already used: this server's graceful shutdown (ADR 0016) exits promptly
once its MCP client disconnects, and an async batch processor's pending
spans are not confirmed flushed before process exit. `"Api-Token " +
apiToken`, not `"Bearer "` — Dynatrace's OTLP ingest requires this exact
header format.

```java
@Bean
@ConditionalOnProperty(prefix = "db2mcp.dynatrace", name = "enabled", havingValue = "true")
public MeterRegistry dynatraceMeterRegistry(DynatraceExportProperties properties) {
    DynatraceConfig config = new DynatraceConfig() {
        @Override public String get(String key) { return null; }
        @Override public String apiToken() { return properties.apiToken(); }
        @Override public String uri() { return properties.environmentUrl() + "/api/v2/metrics/ingest"; }
        @Override public DynatraceApiVersion apiVersion() { return DynatraceApiVersion.V2; }
    };
    return DynatraceMeterRegistry.builder(config).clock(Clock.SYSTEM).build();
}
```

When enabled, both beans register additively alongside
`ObservabilityExportConfig`'s existing beans, not in place of them — ADR
0030's own rationale (a demonstrable, always-inspectable local record of
what was exported) matters doubly here, since this environment cannot
verify a live Dynatrace round-trip; the stderr lines remain the only
verification path actually available in this session.

`EffectiveConfigReporter` is deliberately never given `DynatraceExportProperties`
as a dependency — the same exclusion-by-construction pattern already
applied there to DB2 credentials, applied here to the whole properties
record.

`Db2McpServerApplication` excludes Spring Boot's own
`DynatraceMetricsExportAutoConfiguration` (see Context — a real bean-name
collision this ADR's own bean would otherwise hit).

## Explicitly out of scope
- Reporting the Dynatrace export flag/endpoint via `getCapabilities`/
  `EffectiveLimits` — not requested for this thread, kept minimal;
  `QueryComplexityLimitsProperties` (ADR 0034) was similarly never added to
  `EffectiveConfigReporter`, an established precedent for leaving newer
  `db2mcp.*` properties records out of that reporter unless a client-
  visibility need is identified separately.
- A shell-level, packaged-jar Dynatrace smoke test. The fake ingest server
  used for verification is JVM-only (`com.sun.net.httpserver.HttpServer`);
  standing up an equivalent in bash would be a second, weaker
  implementation just to mirror `scripts/smoke-test.sh`'s style.
  `DynatraceObservabilityIntegrationTest`'s `@SpringBootTest` is the "real
  Spring wiring" evidence instead; `scripts/smoke-test.sh`/
  `eof-shutdown-race-test.sh` are re-run unmodified (Dynatrace disabled by
  default) to confirm no regression to bean wiring or shutdown timing.
- Verifying a real round trip against a live Dynatrace tenant — see
  Verification.

## Consequences
- Every MCP tool call's spans and metrics can now be exported to a real
  Dynatrace tenant, opt-in, config-driven, with no code path that logs the
  API token anywhere.
- `Db2McpServerApplication` gained an autoconfiguration exclusion
  (`DynatraceMetricsExportAutoConfiguration`) it didn't need before this
  thread — a direct consequence of adding `micrometer-registry-dynatrace`
  to the classpath at all, not something that could be deferred.
- No change to the MCP tool contract: `McpContractSnapshotTest`/
  `McpOutputContractSnapshotTest` both pass with **no diff**.
- Full test suite grew from 287 (after thread 4) to 307 tests.
- One pre-existing, unrelated test bug was found and fixed in passing:
  `Db2ConnectivityCheckerTest.neverPropagatesARuntimeExceptionFromThePool`
  asserted only one log line where the production code (`Db2ConnectivityChecker`)
  has always logged two (a WARN plus a DEBUG detail line) whenever
  `io.github.marekpompura.db2mcp`'s configured `DEBUG` level
  (`logback-spring.xml`) is actually in effect — the assertion only ever
  passed by accident, before any Spring Boot context in the same test JVM
  had initialized Logback with that level applied. This thread's new
  `@SpringBootTest`-based Dynatrace tests changed test execution enough to
  surface it consistently; fixed to assert the real, intended two-line
  behavior.

## Verification / enforcement
`./mvnw verify -DskipITs` — full suite green (307 tests), including:
`DynatraceExportPropertiesValidatorTest` (fail-fast behavior, and the
token-never-in-message guarantee); `DynatraceObservabilityConfigTest`
(bean factory methods run directly against `FakeDynatraceServer`: correct
path per signal, exact `"Api-Token "` header format, real span/metric
names reaching the request bodies, and a dedicated leak-detection test
confirmed to genuinely catch a leak — verified by temporarily reintroducing
one, confirming the test failed, then reverting); `DynatraceObservabilityIntegrationTest`
(`@SpringBootTest`, real Spring wiring, a real tool call exported to both
logging and Dynatrace simultaneously, token never in application logs);
`DynatraceDisabledIntegrationTest` (proves the Dynatrace beans genuinely
don't exist in the context when disabled, not merely behave as no-ops);
`DynatraceStartupFailureIntegrationTest` (a real, minimal
`SpringApplication.run()` genuinely fails startup on `enabled=true` with a
missing token). ArchUnit re-run unchanged (no new layering shape). JaCoCo
`policy.*` gate unaffected by design — this code lives in
`adapter.observability`, the same category as `AuditProperties`
(infrastructure wiring, not an ALLOW/REJECT policy decision), not
artificially relocated under `policy.*` to inherit that gate.

`scripts/smoke-test.sh` and `scripts/eof-shutdown-race-test.sh` re-run
against the packaged jar, Dynatrace disabled by default, confirming no
regression to bean wiring or shutdown timing.

## Live-Dynatrace verification (run 2026-09-01, against a real personal trial tenant)
Run against a real personal Dynatrace trial tenant (never a work-issued
one, per this project's corporate-independence standing rule), with
`db2mcp.dynatrace.enabled=true` and a real API token scoped to just
`openTelemetryTrace.ingest`/`metrics.ingest`. Confirmed via the tenant's
own Grail/DQL notebooks and Distributed Tracing Explorer, not just this
server's own stderr:

- **Clean startup - PASS.** No `DynatraceConfigurationException`, real
  credentials, `enabled=true`.
- **No credential leak - PASS.** Grepped full `stderr` for the literal
  token string across every run: zero matches.
- **Metrics export - PASS, confirmed landing.** `mcp.tool.calls` queried
  back via DQL (`timeseries value = sum(mcp.tool.calls), from: -13h,
  interval: 13h`) returned a real, non-null cumulative value (`4.0`) from
  several short-lived test runs, including ones that only ran ~5 seconds
  before exiting - confirms the metrics path survives even a fast
  shutdown.
- **Trace export - FAIL, confirmed real, root-caused.** The Distributed
  Tracing Explorer showed zero spans across every one of ~4 separate app
  runs, despite `stderr` showing each span was created and handed to the
  Dynatrace `SpanProcessor` with no error logged. Ruled out endpoint/auth/
  payload-format as the cause by hand-building a correct OTLP protobuf
  `ExportTraceServiceRequest` and POSTing it directly via `curl` to the
  exact same endpoint with the same token: `HTTP 200`, and that span
  appeared correctly in the Explorer within seconds (`Ok` status, correct
  duration, fully indexed) - proving the endpoint/auth/format are all
  genuinely correct and the problem is specific to how this server
  dispatches its own export.

  Root cause: `SimpleSpanProcessor.onEnd()` calls `exporter.export(...)`
  synchronously the instant a span ends, but `OtlpHttpSpanExporter.export()`
  itself dispatches the actual HTTP call asynchronously (OkHttp, background
  thread) and returns a `CompletableResultCode` nothing in this project's
  shutdown sequence waits on. In the observed runs the span was created
  ~250ms before `StdioLifecycleConfig`'s EOF-triggered shutdown sequence
  started, and `System.exit()` ran shortly after - the real network call
  (unlike the local `FakeDynatraceServer` this ADR's own test suite uses,
  which responds near-instantly) never got a chance to complete. Metrics
  don't share this problem because `DynatraceMeterRegistry.close()` is
  invoked as a normal, synchronously-awaited part of Spring's bean
  destruction during `context.close()` (which this project's shutdown
  thread does wait for), not a fire-and-forget dispatch the way the span
  exporter's async HTTP call is.

  Filed as issue #33.

  **Fix - applied and re-verified live.** Added an explicit
  `sdkTracerProvider.forceFlush().join(shutdownGracePeriod.toMillis(),
  TimeUnit.MILLISECONDS)` to `StdioLifecycleConfig.awaitDrainThenShutdown`,
  immediately before `SpringApplication.exit(...)`/`System.exit(...)` - the
  same pattern `ObservabilityExportIntegrationTest` already used in its own
  test code, now applied to the actual production shutdown path. Bounded by
  the same `shutdownGracePeriod` already governing the rest of this bean's
  shutdown sequence, not a second separately-configured timeout.

  Re-ran the identical live-tenant scenario against the fixed jar: `stderr`
  showed the same span creation and a clean shutdown sequence with no new
  errors/warnings, and this time the Distributed Tracing Explorer showed the
  `mcp.tool getCapabilities` span (`Ok`, ~1.32s duration, correct service
  `db2-mcp-server`) landing within seconds of the run, with no manual
  protobuf workaround involved - the application's own export path now
  completes before the process exits. Issue #33 closed.

- **Real API-token validation behavior server-side, Managed/on-prem
  Dynatrace URL-shape edge cases**: still genuinely unverified from this
  environment (only a SaaS trial tenant was available) - unrelated to the
  trace-export finding above, narrower gaps this ADR's original text
  already flagged correctly.

A checklist item in `docs/demo/guided-scenarios.md` tracks this, mirroring
ADR 0033's live-DB2 checklist item.

## Related decisions
ADR 0004 (credential-via-env-var pattern), ADR 0009 (H2-vs-live-external-
service verification tiers — why the real-tenant round trip is documented,
not tested, here), ADR 0015 (`EffectiveConfigReporter`'s exclusion-by-
construction pattern, explicitly not extended to the Dynatrace token), ADR
0016 (graceful shutdown, the `SimpleSpanProcessor` reasoning cited, not
re-derived), ADR 0018 (`McpToolException`/error model — unrelated to
export, but `DynatraceConfigurationException` follows the same distinct-
exception-type precedent as `AuditFileUnavailableException`), ADR 0028
(explicit-config/fail-fast precedent this ADR's config model follows), ADR
0030 (the logging-export baseline this ADR extends, additively, and whose
hand-wired-beans-over-autoconfiguration choice this ADR's autoconfiguration
exclusion stays consistent with).
