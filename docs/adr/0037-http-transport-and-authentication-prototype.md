# ADR 0037: HTTP transport and authentication prototype (Phase 5 thread 6)

## Status
**Accepted — publication appropriate within the stated experimental
prototype boundary.**

This acceptance does **not** mean production readiness, enterprise
authentication support, or approval for remote production deployment. The
boundary it is accepted within is: opt-in, **disabled by default**,
loopback-only (`server.address: 127.0.0.1`), gated by a single shared
bearer token, with no TLS of its own and no token rotation or revocation.

The promotion gate this ADR set for itself — non-promotable until a
dedicated security review closes — was closed by
[`docs/security/http-transport-security-review-2026-09-21.md`](../security/http-transport-security-review-2026-09-21.md).
That review raised two P1 findings, both fixed and regression-tested, and
two P2 findings, both accepted with recorded reasons. See this ADR's
Consequences for what it found.

## Date
Proposed 2026-08-28. Accepted 2026-09-21, after the dedicated review.

## Context
`docs/research/phase5-roadmap.md` §6 sequences this as thread 6, the last
active thread (thread 7 stays deliberately deprioritized/private-only). ADR
0001 decided stdio-only, with the trust boundary being the OS process — the
spawning process is the caller, no auth exists. ADR 0001's own Consequences
section anticipates this exact thread: "Any future multi-user or
shared-service requirement needs a new ADR. Moving to HTTP/SSE would reopen
questions this ADR deliberately avoided: **authentication, per-user
authorization, transport encryption, and rate limiting.**" ADR 0001 stays
`Accepted` — stdio remains the default; this ADR is additive, not a
replacement.

**Roadmap-doc staleness found and fixed**: `phase5-roadmap.md` §6 said the
pinned SDK was `mcp:0.10.0`, needing an upgrade for Streamable HTTP. That
was stale — the SDK was bumped to `spring-ai-starter-mcp-server:2.0.0` /
`mcp-core:2.0.0` in commit `8c442a7` (2026-08-17), which already ships
`HttpServletStreamableServerTransportProvider` directly (confirmed via
`javap` against the actual jar, not assumed). No SDK upgrade was needed.

**User's binding scope decisions** (via `AskUserQuestion`, before any code
was written):
1. Real, runnable, opt-in code — same rigor as threads 4/5, off by default.
2. Auth: a single static bearer token, env-var-supplied — not OAuth 2.1
   (the master brief's own suggestion, §13.2/§15.8, explicitly rejected as
   too large a scope for a first prototype).
3. `CapabilityProfile` stays a single process-wide value — auth is a binary
   yes/no gate on the whole server, not per-client profiles.
4. Additive: stdio stays wired exactly as today and remains the default.

## Decision drivers
- `McpServerAutoConfiguration.mcpSyncServer(...)`/`.mcpAsyncServer(...)`
  (`spring-ai-autoconfigure-mcp-server-common:2.0.0`) each take exactly
  **one** `McpServerTransportProviderBase` parameter — confirmed directly
  via `javap`, not a `List`/`ObjectProvider`. `McpServerTransportProviderBase`
  is the shared interface both `McpServerTransportProvider` (stdio) and
  `McpStreamableServerTransportProvider` (HTTP) extend — also confirmed via
  `javap`. So this MCP SDK/Spring AI version genuinely wires exactly one
  active transport provider into the running server; stdio and HTTP are a
  genuine XOR switch between two mutually exclusive server *modes*, not
  simultaneous multi-transport.
- This project's established convention (`StdioLifecycleConfig` already
  replaces Spring AI's own default stdio bean; ADR 0036/thread 5 had to
  fight off a competing autoconfiguration once already) is to hand-wire and
  understand every autoconfiguration pulled in, not lean on framework
  magic — so the HTTP transport is hand-wired directly from the SDK's own
  `HttpServletStreamableServerTransportProvider`, not
  `spring-ai-starter-mcp-server-webmvc` (which would add a second,
  competing autoconfiguration module plus the full Spring MVC stack this
  server has no use for).
- ADR 0004's env-var-only credential pattern and ADR 0015/0036's
  exclusion-by-construction discipline (a secret-holding properties record
  is never injected into `EffectiveConfigReporter` at all) extend directly
  to the new bearer token.

## Decision

### Transport dependency: `spring-boot-starter-tomcat` + `spring-web`, not `spring-ai-starter-mcp-server-webmvc`

Confirmed via the real POM graph (BOM-managed at `4.1.0`, no explicit
`<version>` needed): `spring-boot-starter-tomcat` → `tomcat-embed-core:11.0.22`
(bundles the `jakarta.servlet.*` API) + `spring-boot-tomcat` +
`spring-boot-web-server`. No `spring-webmvc`, no `spring-boot-starter-web`
anywhere in that graph, confirmed by `mvn dependency:tree` before and after
— so `DispatcherServletAutoConfiguration`/`WebMvcAutoConfiguration` never
activate. Just an embedded Tomcat hosting exactly the servlet/filter this
project registers by hand.

**A real gap found and fixed during implementation, independent of the
transport-provider choice above**: Spring Boot 4.1 relocated
`ServletWebServerApplicationContext` out of `spring-boot`'s own jar into a
new `spring-boot-web-server` module (`org.springframework.boot.web.server.servlet.context`
package, confirmed via `unzip -l`/`javap`) — but that relocated class
still `extends org.springframework.web.context.support.GenericWebApplicationContext`,
a class that lives in `spring-web`, a module genuinely absent from this
project's dependency tree before this thread (confirmed: `grep spring-web`
against the full resolved tree found nothing). Without `spring-web` on the
classpath, `spring.main.web-application-type: servlet` fails at startup
with a missing class the moment it's set, even though `tomcat-embed-core`
alone already provides the servlet API. Fixed by adding `spring-web`
explicitly as its own dependency — this is *not* `spring-webmvc`
(`DispatcherServlet` stays absent, reconfirmed via `mvn dependency:tree`
after adding it); `spring-web` only supplies the core web
application-context abstractions every servlet-based Spring Boot app
needs, independent of whether Spring MVC is present.

### Config model (mirrors `DynatraceExportProperties`/`DynatraceExportPropertiesValidator`, ADR 0036, exactly)

New package `io.github.marekpompura.db2mcp.configuration.http` (a
subpackage of `..configuration..`, already covered by
`LayeringArchitectureTest`'s existing wildcard rule — confirmed, no new
ArchUnit rule needed).

```java
@Validated
@ConfigurationProperties(prefix = "db2mcp.http")
public record HttpTransportProperties(
        @DefaultValue("false") boolean enabled, @DefaultValue("") String bearerToken) {
}
```

No `port` field — `server.port` is already Spring Boot's own standard
servlet-container property; a duplicate `db2mcp.http.port` would just be a
second knob that can drift.

`HttpTransportPropertiesValidator implements ApplicationRunner`: no-op when
`enabled=false`; when `enabled=true`, throws a new
`HttpTransportConfigurationException` (mirrors `DynatraceConfigurationException`'s
shape, interpolates only property *names*, never the token value — guarded
by `run_exceptionMessage_neverContainsTheBearerTokenValue`) if
`bearerToken()` is blank, **and** a second check: if
`spring.main.web-application-type` (read via injected `Environment`) is not
`"servlet"`. That second check exists for a real, distinct footgun this
design has: `db2mcp.http.enabled` and `spring.main.web-application-type`
are two separate keys an operator must set in sync. Set only the first,
and the app boots as a non-web context — the `@ConditionalOnProperty`-gated
HTTP beans never register, nothing listens on any port, and nothing is
visibly wrong without this check turning it into a loud, fail-fast startup
error naming the property explicitly.

`EffectiveConfigReporter` never gets `HttpTransportProperties` injected —
same exclusion-by-construction discipline as the DB2 credentials and the
Dynatrace API token.

### `spring.main.web-application-type` toggle

```yaml
spring:
  main:
    web-application-type: ${DB2MCP_HTTP_WEB_APPLICATION_TYPE:none}
server:
  port: 8080
  address: 127.0.0.1   # loopback-only even when HTTP is enabled - see Explicitly out of scope (TLS)
db2mcp:
  http:
    enabled: ${DB2MCP_HTTP_ENABLED:false}
    bearer-token: ${DB2MCP_HTTP_BEARER_TOKEN:}
```

A plain `application.yml` entry is genuinely sufficient for real
production startup: `spring.main.*` is bound onto the `SpringApplication`
instance from the environment during `prepareEnvironment()`, before
`createApplicationContext()` decides which context type to build, and
`application.yml`'s placeholder resolves against real env vars at normal
precedence — the same path every other property here already correctly
uses. This is **not** thread 5's `SpringApplicationBuilder.properties(Map)`
bug recurring (that overload is never used in this project's real
`main()`); confirmed directly by empirically testing the disabled path
(default `web-application-type: none` held correctly under
`@SpringBootTest`'s default web environment even with `spring-web`/
`spring-boot-starter-tomcat` now on the classpath — no accidental
classpath-based auto-detection escalation for any of this project's many
other `@SpringBootTest` classes).

**A second, genuinely new timing bug found while writing this ADR's own
end-to-end test, distinct from both of the above**: `@DynamicPropertySource`
values are added to the test environment via an `ApplicationContextInitializer`
callback that runs during `prepareContext()` — strictly *after*
`SpringApplication`'s `prepareEnvironment()` step has already read and
bound `spring.main.web-application-type` (still application.yml's default
`none` at that point). A test that sets
`spring.main.web-application-type=servlet` only via
`@DynamicPropertySource` silently never gets a real embedded servlet
container — reproduced directly (`local.server.port` never resolved).
Fixed by using `@TestPropertySource(properties = "spring.main.web-application-type=servlet")`
instead for that one property, which is folded into the environment before
`SpringApplication.run()` is invoked. This is purely a Spring Boot Test
bootstrap-ordering quirk specific to how the test harness assembles the
environment; real production startup (a single `SpringApplication.run(args)`
reading the fully-resolved environment once) is unaffected — a real
`DB2MCP_HTTP_WEB_APPLICATION_TYPE=servlet` env var works correctly there,
following the exact same code path already relied on for every other
property in this project.

### Bean wiring — new `HttpTransportConfig`

Four `@ConditionalOnProperty(prefix = "db2mcp.http", name = "enabled", havingValue = "true")`
beans: the transport provider itself, a `ServletRegistrationBean` mapping
it to `/mcp/*`, a `FilterRegistrationBean` wrapping
`BearerTokenAuthenticationFilter` at `/*` with `Ordered.HIGHEST_PRECEDENCE`,
and (added after a self-review found a real gap — see below) a fallback
catch-all `HttpServlet` also registered at `/*`.

```java
@Bean
@ConditionalOnProperty(prefix = "db2mcp.http", name = "enabled", havingValue = "true")
public McpStreamableServerTransportProvider httpServerTransport() {
    return HttpServletStreamableServerTransportProvider.builder()
            .jsonMapper(new JacksonMcpJsonMapper(JsonMapper.builder().build()))
            .mcpEndpoint("/mcp")
            .build();
}
```

Same `JacksonMcpJsonMapper(JsonMapper.builder().build())` construction
`StdioLifecycleConfig` already uses, for the same documented reason:
Spring AI's own autoconfigured `mcpServerJsonMapper` bean doesn't reliably
instantiate in time in the packaged jar's bootstrap ordering.

**Required edit to existing, `Accepted` code**: `StdioLifecycleConfig.stdioServerTransport(...)`
gained `@ConditionalOnProperty(prefix = "db2mcp.http", name = "enabled", havingValue = "false", matchIfMissing = true)`
— without it, both beans would exist simultaneously when HTTP is enabled,
and Spring would throw `NoUniqueBeanDefinitionException` at startup (see
Decision drivers).

### Bearer-token authentication: a plain servlet `Filter`

`HttpServletStreamableServerTransportProvider.Builder` also exposes a
`.securityValidator(ServerTransportSecurityValidator)` SDK-native hook
(confirmed via `javap`) — considered, not used: its exact call timing
relative to request-body/session handling wasn't independently verified,
whereas a plain servlet `Filter` is guaranteed by the servlet spec to run
before `service()` for every verb, and needs no extra dependency
(`OncePerRequestFilter` lives in `spring-web`... but that convenience base
class specifically is skipped anyway, implementing `jakarta.servlet.Filter`
directly to avoid any Spring MVC-adjacent coupling beyond what's already
required).

`BearerTokenAuthenticationFilter`: reads `Authorization`, rejects
missing/malformed/wrong token identically (401, `WWW-Authenticate: Bearer`,
minimal generic JSON body — no oracle distinguishing failure reasons),
compares via `MessageDigest.isEqual(byte[], byte[])` not `String.equals`
(constant-time comparison — an implementation guarantee asserted by code
review, not something the test suite measures via timing), logs a single
WARN via SLF4J on rejection (method/path/remote address, never any token
value).

**Why the rejection body does not reuse `McpToolException`/`ErrorCategory`**:
those are the MCP-protocol-level error taxonomy (ADR 0018), surfaced from
inside `ToolCallback.call(String)`. A filter-level bearer-token rejection
happens before any MCP session exists, before any JSON-RPC message is
parsed, with no tool name and no correlation ID to attach — forcing it
into that taxonomy would be exactly the kind of layer violation ADR 0018
exists to prevent in the other direction. Plain HTTP semantics only.

### A real gap found by self-review, fixed same-day: the filter didn't actually cover every path

`docs/security/http-transport-security-review-2026-08-29.md` (SR-001)
found, by probing a running packaged jar with `curl` rather than trusting
the code's own comment, that `bearerTokenFilterRegistration`'s `/*`
mapping did **not** actually intercept every path. This is a real,
confirmed servlet-container behavior: a `/*`-mapped filter only runs for a
request that resolves to *some* registered servlet; with only `/mcp/*`
registered, Tomcat's own request-mapping short-circuited straight to a
stock 404 for any other path, bypassing the filter chain entirely — proven
by the filter's own rejection log line firing for `/mcp` but never for
`/`, `/actuator/health`, or `/favicon.ico` in the same probe run.

Current impact was low (no other servlet exists in this app to expose —
`spring-webmvc`'s absence means Actuator never self-registers a web
endpoint either), but it directly contradicted the filter's own documented
intent and was a silent trap for any future endpoint. Fixed by adding a
fourth bean, `fallbackNotFoundServletRegistration` — a trivial
`HttpServlet` (`service()` just calls `resp.sendError(404)`) also
registered at `/*`. This guarantees every path resolves to *some* servlet
(this one, if nothing more specific matches), so the filter chain always
runs first; `/mcp/*` remains a strictly more specific prefix mapping and
still wins for its own paths (Servlet spec §12.2's mapping-precedence
rules). Re-ran the exact same `curl` probe that found the gap against the
rebuilt jar to confirm the fix: all of `/`, `/actuator/health`,
`/favicon.ico`, and `/mcp` now correctly return 401 without a token, with
the filter's rejection log line firing for all four.

The same self-review (SR-002) also found `HttpTransportPropertiesValidator`
only checked the token was non-blank, not that it had any real length —
a one-character token would have passed validation, making the filter's
constant-time comparison moot (constant-time only protects a secret that
has entropy to begin with). Fixed by adding a 16-character minimum-length
check, same fail-fast-at-startup shape as the existing blank/web-
application-type checks, interpolating only the token's length (a
non-sensitive integer) into the exception message, never the value.

## Explicitly out of scope
- **OAuth 2.1** — user's explicit call; single bearer token instead. The
  natural next step if this thread is ever promoted.
- **Per-client capability profiles** — `CapabilityProfile`
  (`db2mcp.capabilityProfile`) stays the single process-wide value it
  already is, resolved once at Spring startup and baked into the fixed
  tool-callback list (`McpConfig.schemaToolCallbackProvider`); auth here is
  a binary server-wide gate, not per-connection identity resolution. A
  known, unaddressed gap for a future thread.
- **TLS** — plain HTTP accepted for *this* prototype specifically, with a
  hard, explicit caveat: never deploy without TLS terminated in front (a
  reverse proxy or service mesh sidecar). The concrete mitigation is
  `server.address: 127.0.0.1` (loopback-only by default) — the load-bearing
  reason plain HTTP is acceptable here, not a general endorsement of
  unencrypted MCP traffic. Operators must deliberately widen the bind
  address.
- **Rate limiting** — not built. `AdmissionControlledToolCallback`'s
  existing concurrency gate (ADR 0035) still applies uniformly regardless
  of transport (it wraps `ToolCallbackProvider`, transport-agnostic) — some
  backpressure exists, just not HTTP-request-rate-specific throttling.
- **HTTP-transport-aware graceful shutdown/drain** — ADR 0016/0031's
  mechanisms (`EofTriggeredShutdownInputStream`, `InFlightRequestTracker`,
  `RequestIdTrackingInputStream`/`ResponseIdTrackingOutputStream`) wrap
  `System.in`/`System.out` bytes directly and have no meaning for a
  servlet transport. Spring Boot's generic `server.shutdown: graceful` +
  the existing `spring.lifecycle.timeout-per-shutdown-phase: 10s` covers
  the servlet container's own in-flight-request draining reasonably, but
  has no MCP-session-level drain guarantee equivalent to ADR 0031's. A
  known, unaddressed gap, not attempted in this pass.
- **Bearer token rotation** — `db2mcp.http.bearerToken` is a single
  static config value with no expiry, versioning, or automated rotation.
  An operator can rotate manually (change the property, restart the
  process), but nothing enforces or schedules it, and a restart
  invalidates every client's token at once rather than allowing a
  single leaked token to be revoked in isolation. Implied by this ADR's
  single-static-token design from the start, but not previously named
  explicitly here — surfaced by the 2026-09-01 master-brief gap review
  (P2-3) and named here per
  issue #47,
  alongside the threat model's new H7 row.

## Consequences
- A real, opt-in HTTP transport now exists alongside stdio, gated fully
  off by default (`db2mcp.http.enabled=false`, `spring.main.web-application-type: none`)
  — zero network exposure unless both are deliberately flipped in sync,
  ADR 0001's guarantee holds for the default case.
- Two genuinely new dependencies (`spring-boot-starter-tomcat`,
  `spring-web`) are now always on the classpath, even when HTTP is
  disabled — confirmed by direct empirical test
  (`HttpTransportDisabledIntegrationTest`) that this causes **no**
  behavior change to any of this project's many other `@SpringBootTest`
  classes: `spring.main.web-application-type: none`'s explicit default in
  `application.yml` correctly overrides classpath-based `WebApplicationType`
  auto-detection.
- `StdioLifecycleConfig`, previously unconditional, is now
  `@ConditionalOnProperty`-gated — a direct, necessary consequence of the
  single-active-transport constraint (see Decision drivers), not an
  incidental change.
- No change to the MCP tool contract: `McpContractSnapshotTest`/
  `McpOutputContractSnapshotTest` both pass with **no diff** — this thread
  adds a transport + auth layer, not new tools.
- Full test suite grew from 307 (after thread 5) to 327 tests.
- Two genuinely new Spring Boot/Spring Test bootstrap-ordering findings,
  independent of each other, both confirmed by direct reproduction rather
  than assumed: the `spring-boot-web-server`/`spring-web` split (Decision,
  Transport dependency) and the `@DynamicPropertySource`-too-late-for-
  `WebApplicationType` timing gotcha (Decision, toggle mechanism). Neither
  affects real production startup — both are specific to how this thread's
  own test infrastructure needed to be built.
- A same-day self-review (`docs/security/http-transport-security-review-2026-08-29.md`)
  found and fixed two real gaps beyond what the initial implementation's
  own test suite caught: the bearer-token filter didn't actually cover
  every path (SR-001, fixed via a fallback catch-all servlet) and the
  token had no minimum-length enforcement (SR-002, fixed via a 16-character
  floor). Both closed same-day, each with a new regression test proving
  the specific gap the review found, not just a general "more tests" pass.

## Outcome of the dedicated security review (2026-09-21)

The gate this ADR set for itself is closed. Full record:
[`docs/security/http-transport-security-review-2026-09-21.md`](../security/http-transport-security-review-2026-09-21.md).

**Two P1 findings, both fixed and regression-tested.**

- **HTTP-P1-1 — validation ran after the port was bound.**
  `HttpTransportPropertiesValidator` is an `ApplicationRunner`, and Spring
  Boot starts the embedded container during context *refresh*; runners
  execute afterwards. So with `enabled=true` and a blank token, Tomcat had
  already bound `127.0.0.1:8080` before the validator threw. The blank and
  minimum-length checks moved into `BearerTokenAuthenticationFilter`'s
  constructor, which runs during refresh — a throw there aborts startup
  before the web server starts.
- **HTTP-P1-2 — a blank expected token authenticated every request.** The
  filter stored the token without validating it, and
  `MessageDigest.isEqual(empty, empty)` is `true`, so `Authorization:
  Bearer ` with an empty credential passed. Closed by the same change.

A secondary consequence: a throwing constructor trips SpotBugs'
`CT_CONSTRUCTOR_THROW`, so `BearerTokenAuthenticationFilter` is now
`final` — removing the finalizer-attack surface rather than excluding the
detector.

**Two P2 findings, accepted with reasons.**

- **HTTP-P2-1 — no request-size or rate limits**, so container defaults
  apply and repeated authentication failures are unbounded. Accepted for a
  disabled-by-default loopback prototype whose worst case is a local denial
  of service by a caller who already holds the token. Revisit before any
  non-loopback deployment.
- **HTTP-P2-2 — servlet-mode startup ordering was untested**, because the
  startup-failure test used `WebApplicationType.NONE` to avoid an unrelated
  stdin-thread hang. Closed by HTTP-P1-1's test change.

**Examined and found sound**, so recorded here to save the next reader the
work: the `@ConditionalOnProperty` gating genuinely removes the beans;
`server.address: 127.0.0.1` is really configured, not only documented; the
token comparison is constant-time; missing, malformed and wrong credentials
are indistinguishable in the response; `fallbackNotFoundServlet` genuinely
closes the unmapped-path filter bypass; no Actuator HTTP surface exists
(no `spring-webmvc`, so no `DispatcherServlet`); and HTTP requests traverse
the identical policy chain, since `McpConfig` wraps every tool callback
transport-agnostically.

## Verification
`./mvnw verify -DskipITs` — full suite green (327 tests, run twice in a
row for stability, including after the SR-001/SR-002 fixes below), including:
- `HttpTransportPropertiesValidatorTest` (fail-fast behavior for the
  blank-token, too-short-token, and wrong-web-application-type cases, and
  the token-never-in-message guarantee).
- `BearerTokenAuthenticationFilterTest` (missing/malformed/wrong-same-length/
  wrong-different-length token rejection, correct-token pass-through,
  rejection body never contains the token).
- `HttpTransportIntegrationTest` (`@SpringBootTest(webEnvironment = RANDOM_PORT)`,
  a real embedded Tomcat on a random port): a raw `java.net.http.HttpClient`
  probe with no/wrong token → 401, leak-checked; the same probe against an
  arbitrary unmapped path (not just `/mcp`) → 401, proving SR-001 stays
  fixed; a real MCP client using the SDK's own `HttpClientStreamableHttpTransport`
  (confirmed via `javap`: `.requestBuilder(HttpRequest.Builder)` to attach
  `Authorization: Bearer <token>`) performing a genuine `initialize`
  handshake plus a real `getCapabilities` tool call end-to-end with the
  correct token; the same real client with the wrong token fails to
  initialize (confirmed directly: the SDK surfaces this as a plain
  `RuntimeException` from `LifecycleInitializer`, not a more specific
  typed exception an earlier version of this test assumed).
- `HttpTransportDisabledIntegrationTest`: default config, asserts no
  `McpStreamableServerTransportProvider`/`Filter` beans exist **and** the
  `ApplicationContext` itself is genuinely not a `WebApplicationContext` —
  the actual "no open port" proof, not merely "the beans behave as
  no-ops."
- `HttpTransportStartupFailureIntegrationTest`: a real, minimal
  `SpringApplication.run()` genuinely fails startup on `enabled=true` with
  a missing bearer token.
- Leak-detection methodology, same procedure as ADR 0036: a token leak was
  temporarily reintroduced into the filter's rejection body, confirmed
  `BearerTokenAuthenticationFilterTest` genuinely failed with the token
  visible in the assertion diff, then reverted and the suite reconfirmed
  green.
- SR-001's fix was verified the same way the gap itself was found — not
  just by the new automated test, but by rebuilding the packaged jar and
  re-running the exact `curl` probe that originally found it: `/`,
  `/actuator/health`, `/favicon.ico`, and `/mcp` all correctly returned
  401 without a token afterward, with the filter's own rejection log line
  firing four times (one per path), confirmed via
  `grep -c "Rejected unauthenticated"` against the real stderr output.

ArchUnit re-run unchanged (`configuration.http` already covered by the
existing `..configuration..` wildcard rule). JaCoCo `policy.*` gate
unaffected by design — this thread's new code isn't in `policy.*`.

`scripts/smoke-test.sh`/`scripts/eof-shutdown-race-test.sh` re-run
unmodified against the packaged jar, HTTP disabled by default, confirming
no regression to stdio wiring or shutdown timing.

**Genuinely not verifiable from this environment**: real third-party MCP
HTTP client interop (Claude Desktop/Code, or another language's client) —
in particular, the MCP spec's own OAuth-discovery-first authorization
guidance means some real clients might probe for OAuth metadata before
attempting a bare bearer token, which this prototype's plain-401 rejection
hasn't been checked against. Unlike ADR 0033/0036, this gap isn't a
missing live external system — everything here is fully locally
verifiable; the reason this ADR *remained* `Proposed` and explicitly
non-promotable was the roadmap's own framing (`docs/research/phase5-roadmap.md`
§6: "explicitly marked non-promotable until a dedicated security review
closes"), given HTTP transport's genuinely different risk profile
(inbound network exposure) compared to every other thread in this phase.
**That review completed on 2026-09-21 and this ADR is now Accepted within
the experimental prototype boundary stated in its Status.** The
OAuth-metadata probing gap described above is unchanged and remains an
accepted residual risk.

## Related decisions
ADR 0001 (stdio transport, the decision this thread reopens per its own
Consequences section), ADR 0004 (env-var credential pattern, extended to
the bearer token), ADR 0015 (`EffectiveConfigReporter`'s exclusion-by-
construction pattern, extended here), ADR 0016 (graceful shutdown — its
`spring.lifecycle.timeout-per-shutdown-phase` is reused unmodified; its
stdin-EOF mechanism does not carry over, see Explicitly out of scope), ADR
0018 (`McpToolException`/error model — the layer boundary this ADR's
rejection-response design deliberately respects), ADR 0031 (in-flight
request draining — its guarantee does not carry over to HTTP, a known
gap), ADR 0035 (`AdmissionControlledToolCallback`'s concurrency gate,
confirmed to still apply transport-agnostically), ADR 0036 (the most
recent worked example of this project's opt-in/`@ConditionalOnProperty`/
fail-fast-validator/exclusion-by-construction pattern, followed closely
here).
