# ADR 0030: Local, logging-based export for metrics and traces (CR-010)

## Status
Accepted — implemented and verified in this environment (`mvn verify
-DskipITs` green, 198 tests, up from 191; verified against the real
packaged jar via `scripts/smoke-test.sh`, not just unit tests).

## Date
Proposed 2026-08-17, Accepted 2026-08-17.

## Context
CR-010 (2026-08-14 code review) flagged a real gap: `MeterRegistry` and
`Tracer` beans existed and were genuinely instrumented (ADR 0011, ADR
0012) - every tool call records metrics and opens/tags/closes spans -
but nothing proved either one's data actually reached anywhere
observable. "Instrumented" and "exported and observable" are, correctly,
two different claims, and this project had only ever demonstrated the
first one.

The review's own suggested remediation offered a choice: "a
localhost-only management endpoint with Prometheus metrics," "OTLP
export to a local collector/Jaeger," or "an explicitly configured
logging exporter bean." The first two were rejected outright (see
Decision drivers); the third is what this ADR implements, applied
symmetrically to both metrics and traces.

## Decision drivers
- A localhost-only HTTP management endpoint is a network listener. This
  contradicts ADR 0001's foundational, repeatedly-reverified property
  (checked via `lsof` in ADR 0011, ADR 0012, ADR 0015, ADR 0019) that
  this server opens no network port of any kind. That property is a
  security posture, not an accident of implementation - a review
  suggestion that would silently undo it doesn't get a pass just because
  it's the more conventional choice.
- OTLP export to a local collector or Jaeger requires an external
  service. None is reachable in this environment, for the same reason
  ADR 0009 has no live Db2 to verify against - there's nothing to
  demonstrate export *to*, so the claim would be exactly as unverified
  as the gap CR-010 flagged in the first place.
- The review names a third option itself - "an explicitly configured
  logging exporter bean" - which needs no network listener and no
  external service, and is directly and repeatedly verifiable against
  this project's existing tools: a live packaged-jar probe, and now
  `scripts/smoke-test.sh`.
- `management.tracing.sampling.probability` defaults to 10% in Spring
  Boot. This is a low-volume, governed internal tool where every call is
  already individually audited (ADR 0010) - sampling exists to reduce
  collector load under high traffic, which isn't a concern here, and a
  sampled-out span would silently defeat the whole point of this fix:
  a demo run that happens to sample out looks identical to export not
  working at all.

## Considered options
1. **Localhost-only Prometheus endpoint.** Rejected - opens a network
   port, contradicting ADR 0001.
2. **OTLP export to a local collector/Jaeger.** Rejected - no such
   service is reachable in this environment (ADR 0009's reasoning).
3. **`LoggingMeterRegistry` + `LoggingSpanExporter`, both writing to
   stderr like every other log line (ADR 0001 - stdout is JSON-RPC
   only).** Chosen. Both are already transitively available
   (`micrometer-core`/`spring-boot-starter-actuator` for the former,
   the already-present `opentelemetry-exporter-logging` dependency for
   the latter) - no new network-facing dependency, no new architectural
   surface, and directly verifiable with the same techniques already
   used throughout this project.
4. **`SpanProcessor` bean (`SimpleSpanProcessor` wrapping
   `LoggingSpanExporter`) vs. a bare `SpanExporter` bean.** Chosen over
   a bare exporter bean - see the found bug below. Spring Boot's
   `OpenTelemetryTracingAutoConfiguration` auto-wraps any bare
   `SpanExporter` bean in its own default `BatchSpanProcessor`, which
   batches asynchronously; this server's graceful shutdown (ADR 0016)
   closes the Spring context and exits promptly once its MCP client
   disconnects, and the batch processor's pending spans were never
   confirmed flushed before process exit. `SimpleSpanProcessor` exports
   synchronously, per span, on `end()` - no batching delay to race
   against shutdown, and no cost that matters at this server's actual
   call volume (a governed internal tool, not a high-throughput
   service).

## Decision
`ObservabilityExportConfig` (`src/main/java/.../configuration/`) defines
two beans:
- `loggingSpanProcessor()`: a `SpanProcessor` bean -
  `SimpleSpanProcessor.create(LoggingSpanExporter.create())` - not a
  bare `SpanExporter` bean, for the reason above.
- `loggingMeterRegistry()`: a plain `LoggingMeterRegistry` bean, which
  joins the same `CompositeMeterRegistry`
  `MetricsRecorder` already writes to, so every existing meter
  (`mcp.tool.calls`, `mcp.tool.duration`, `db2.query.duration`, etc.) is
  exported through it automatically with no change to `MetricsRecorder`
  itself.

`management.tracing.sampling.probability: 1.0` in `application.yml`
(100%, not the 10% default) - justified above.

`Db2McpServerApplication.main()` installs the JUL-to-SLF4J bridge
(`SLF4JBridgeHandler.removeHandlersForRootLogger()` /
`.install()`) as early as possible, per SLF4J's own guidance - see the
found bug below for why this is load-bearing, not defensive. `pom.xml`
pins `org.slf4j:jul-to-slf4j` explicitly (previously only present
transitively via `spring-boot-starter-logging`), since `main()` now
depends on it directly.

`scripts/smoke-test.sh` now asserts on both exports explicitly, not
just displaying a human-facing tail for manual inspection: a
`LoggingSpanExporter` line for the `listSchemas` call (checked before
the SIGTERM shutdown, since spans export synchronously as each call
completes) and a `LoggingMeterRegistry` line (checked after shutdown,
since `LoggingMeterRegistry` only flushes periodically or on `close()`,
and the push interval hasn't elapsed within the test's short window).

## Consequences
- **Three real, found-and-fixed bugs on the way to genuine end-to-end
  export**, each caught by refusing to trust an unverified claim:
  1. **`LoggingSpanExporter` logs via `java.util.logging`, not SLF4J.**
     `jul-to-slf4j` was present transitively but never installed -
     without the bridge, every span export call was a real, verified
     no-op: nothing appeared anywhere, not even a dropped-warning trace.
     Found via a live packaged-jar probe showing metrics exporting
     (`LoggingMeterRegistry` uses SLF4J directly) but zero span output.
     Fixed by installing the bridge in `main()` and pinning the
     dependency explicitly.
  2. **Wrong YAML namespace for the sampling probability.** First
     placed under the top-level `spring:` key
     (`spring.tracing.sampling.probability`), which Spring Boot silently
     ignores - the correct property is
     `management.tracing.sampling.probability`. This left the default
     10% sampling rate active, so most single-call manual probes were
     silently sampled out. Found by writing
     `ObservabilityExportIntegrationTest`, which failed with a
     `ConditionTimeout` even with an explicit `forceFlush()` -
     definitive proof the issue predated any flush-timing concern, not
     an assumption about which layer was at fault.
  3. **Default `BatchSpanProcessor` not reliably flushing before process
     exit.** See "Considered options" above - fixed by registering a
     `SpanProcessor` bean directly instead of a bare `SpanExporter`
     bean. Re-verified against the real packaged jar: both `db2.execute`
     and `mcp.tool getCapabilities` spans now appear reliably, sharing
     the same trace ID, with no forced flush needed.
- **A fourth, unrelated real bug found while verifying the above**: the
  new `ObservabilityExportIntegrationTest` is this project's first
  `@SpringBootTest` - every prior test either avoids booting a real
  Spring context or replaces the MCP transport layer entirely. Booting
  the real context via `classes = Db2McpServerApplication.class` also
  boots the real `stdioServerTransport` bean (`StdioLifecycleConfig`,
  ADR 0016), which wraps `System.in` and reads it on a background
  thread. In the test JVM, nothing ever closes `System.in`, so that
  thread blocks forever in a native `read()` call - and
  `Thread.interrupt()` cannot unblock a plain blocking stream read.
  When the JVM's own shutdown hook tried to close the test's Spring
  context (and, with it, that bean) at the end of the Surefire fork's
  test run, the close hung indefinitely. Surefire has a 30-second
  patience for a forked JVM to exit after `System.exit(0)`; the first
  full-suite run happened to finish just under that window (an
  unreliable coincidence, not evidence of correctness), and a second
  run exceeded it, genuinely failing the build with
  `SurefireBooterForkException`. Reproduced deliberately with a thread
  dump captured mid-hang, confirmed by two clean back-to-back full-suite
  runs after the fix, not trusted on a single green run. Fixed with
  `@MockitoBean private McpServerTransportProvider
  mcpServerTransportProvider;` in the test - this test only needs the
  real tracing/metrics autoconfiguration CR-010 is about, not a live MCP
  transport, so the bean's factory method is replaced with a mock and
  never runs, and the blocking thread never starts.
- **A verification technique adopted for the first time in this
  project**: `@SpringBootTest` + a hand-written `CapturingSpanExporter`
  test double (avoiding a new `opentelemetry-sdk-testing` dependency) +
  `SdkTracerProvider.forceFlush()` + Awaitility, to get definitive,
  unambiguous ground truth about span export against the *real* Spring
  autoconfiguration - `SimpleTracer`-based unit tests (every other test
  in this project) prove spans are opened/tagged/closed correctly, but
  bypass the OTel SDK's exporter pipeline entirely, so they cannot prove
  export happens at all. That gap is exactly what CR-010 flagged and
  exactly what this test closes.
- **`scripts/smoke-test.sh` now makes an assertable claim about export**,
  not just a human-facing tail for manual inspection. That tail itself
  was the source of a false alarm during this work: `LoggingMeterRegistry`'s
  shutdown dump is 40+ lines of JVM metrics, which crowded an
  earlier-logged span line out of the script's `tail -40` display,
  briefly looking like a real regression before a full, untruncated
  capture proved the span had exported correctly all along. The new
  assertions check the full captured stderr, not the truncated display.
- **Full suite: 198 tests** (up from 191) - `ObservabilityExportIntegrationTest`
  adds 1. All green under `mvn verify -DskipITs`, confirmed on two
  consecutive clean runs after the shutdown-hang fix (not a single run).
  JaCoCo policy-package gate and all ArchUnit rules unaffected.

## Verification / enforcement
`mvn verify -DskipITs` (198 tests), run twice consecutively after the
`@MockitoBean` fix to confirm the shutdown hang was genuinely resolved,
not a lucky single pass. `scripts/smoke-test.sh` against the real
packaged jar, extended with explicit `LoggingSpanExporter`/
`LoggingMeterRegistry` assertions. A thread dump captured mid-hang
during diagnosis, not inferred from symptoms alone.

## Related decisions
ADR 0001 (no-network-exposure - the reason the Prometheus-endpoint
option was rejected), ADR 0009 (Tier 1/Tier 2 honesty and no-live-Db2
constraint - the same reasoning that rules out OTLP-to-collector here),
ADR 0011 (MeterRegistry instrumentation, the metrics half this ADR
finally exports), ADR 0012 (Tracer/span instrumentation, the tracing
half this ADR finally exports), ADR 0016 (graceful shutdown - both the
reason `SimpleSpanProcessor` was needed and the mechanism whose real
bean caused the test-shutdown hang this ADR also fixes), ADR 0010
(100% audit coverage - the reasoning behind 100% trace sampling here
too).
