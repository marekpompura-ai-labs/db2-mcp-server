# Dedicated HTTP transport security review — 2026-09-21

**Scope:** the HTTP transport and bearer-token authentication prototype
(ADR 0037), reviewed **as shipped and within its declared boundary** — an
opt-in, disabled-by-default, loopback-only prototype — not as a
production-ready multi-user network service.

**Why it exists:** ADR 0037 and the Phase 5 roadmap both made the
prototype *non-promotable until a dedicated security review closes*. The
earlier self-review
([`http-transport-security-review-2026-08-29.md`](http-transport-security-review-2026-08-29.md))
said explicitly that it was not a substitute for that review. A
pre-publication review of the public edition then flagged that shipping the
component while its own gate was open is a contradiction in the project's
strongest claim. This document closes the gate.

**What this is not:** a penetration test, a certification, a formal audit,
or a regulatory conformity assessment. It is an independent cross-model
review of the implementation, its configuration, its tests and its
documentation.

---

## Findings

| ID | Finding | Severity | Status |
|---|---|---|---|
| HTTP-P1-1 | Bearer-token validation ran after the servlet port was bound | P1 | **Fixed**, regression-tested |
| HTTP-P1-2 | A blank expected token authenticates every request | P1 | **Fixed** by the same change |
| HTTP-P2-1 | No request-size or rate limits configured; container defaults apply | P2 | Accepted, documented |
| HTTP-P2-2 | Startup-failure test could not observe servlet-mode ordering | P2 | Closed by HTTP-P1-1's test change |

### HTTP-P1-1 — validation ran after the port was bound

**Evidence.** `HttpTransportPropertiesValidator` is an
`ApplicationRunner`. Spring Boot starts the embedded servlet container
during context *refresh*; `ApplicationRunner`s execute afterwards. So with
`db2mcp.http.enabled=true` and a blank or too-short bearer token, Tomcat
had already bound `127.0.0.1:8080` before the validator threw.

**Why it mattered.** The validator is the mechanism the design relies on to
guarantee that an enabled HTTP transport is always token-gated. For the
window between bind and throw, that guarantee did not hold — see
HTTP-P1-2, which is what made the window exploitable rather than merely
untidy.

**Fix.** The blank and minimum-length checks moved into
`BearerTokenAuthenticationFilter`'s constructor. The filter is a bean, so
construction happens *during* refresh: a throw there aborts the refresh
before the web server starts. The port is never bound, so there is no
window.

`HttpTransportPropertiesValidator` keeps both checks as a backstop, and
still solely owns the `spring.main.web-application-type` check, which has
no filter-side equivalent.

**Evidence the fix works.** `HttpTransportStartupFailureIntegrationTest`
previously observed `HttpTransportConfigurationException` thrown *after* a
successful bind. It now observes
`ApplicationContextException: Unable to start web server` with that
exception as **root cause** — the wrapper is the proof that the container
never finished starting.

**Secondary finding.** The constructor throw trips SpotBugs'
`CT_CONSTRUCTOR_THROW`: a throwing constructor leaves a partially
initialized object reachable by a finalizer attack. The class is now
`final`, which removes the attack (it requires a malicious subclass) rather
than suppressing the detector. **No exclusion was added** — ADR 0046's
reasoning about exclusions silencing the *next* finding at a line applies
with particular force here.

### HTTP-P1-2 — a blank expected token authenticates every request

**Evidence.** The filter stored the configured token as bytes with no
validation, and `MessageDigest.isEqual(empty, empty)` returns `true`. A
blank expected token therefore matched an empty presented credential:
`Authorization: Bearer ` authenticated.

**Exploitability.** Low on its own — reaching it required the
misconfiguration HTTP-P1-1's validator exists to reject, over a
loopback-only bind, within a window of milliseconds. Reported as P1 despite
that, because the filter is the *declared* gate and a blank token silently
converts it into no gate at all.

**Fix.** Same change as HTTP-P1-1, plus four regression tests in
`BearerTokenAuthenticationFilterTest`: blank, whitespace-only, shorter than
the minimum, and a test asserting the rejection message never contains the
token value.

### HTTP-P2-1 — no request-size or rate limits

No `server.tomcat.*` or `server.max-http-*` limits are configured, so the
embedded container's defaults apply, and repeated authentication failures
are unbounded.

**Accepted for this prototype.** ADR 0037 already records rate limiting as
not built, and `AdmissionControlledToolCallback`'s concurrency gate
(ADR 0035) applies to every transport since it wraps
`ToolCallbackProvider`. Against a disabled-by-default, loopback-only
prototype the residual risk is a local denial of service by a caller who
already holds the token. Revisit before any non-loopback deployment.

### HTTP-P2-2 — servlet-mode startup ordering was untested

`HttpTransportStartupFailureIntegrationTest` used
`WebApplicationType.NONE`, deliberately, to avoid an unrelated stdin-thread
hang. A consequence was that no test could observe what happened in servlet
mode — which is exactly where HTTP-P1-1 lived. Closed by that finding's
test change, which now asserts on the servlet-context failure path.

---

## Verified sound — examined and **not** findings

- **Disabled by default is real, not a runtime no-op.** All four HTTP beans
  are `@ConditionalOnProperty`-gated on `db2mcp.http.enabled`; when
  disabled they do not exist in the context.
- **The loopback bind is actually configured**, not merely documented:
  `server.address: 127.0.0.1` in `application.yml`. ADR 0037 calls this the
  load-bearing mitigation for shipping without TLS, so its presence was
  checked directly rather than taken from the ADR.
- **Constant-time token comparison** via `MessageDigest.isEqual`.
- **No authentication oracle.** Missing header, malformed scheme and wrong
  token all produce an identical 401 with a generic body, so a caller
  cannot distinguish them.
- **The filter cannot be bypassed by an unmapped path.** A servlet-container
  filter only runs for requests that resolve to *some* servlet;
  `fallbackNotFoundServlet` at `/*` guarantees that, which is what the
  earlier self-review (SR-001) found and fixed after observing `/` and
  `/actuator/health` reaching Tomcat's stock 404 without the filter firing.
- **No Actuator HTTP surface.** `spring-webmvc` is deliberately absent, so
  no `DispatcherServlet` exists and Actuator's web endpoints cannot
  register — consistent with the SR-001 observation above.
- **HTTP requests receive the identical policy chain.** `McpConfig` builds
  one `ToolCallbackProvider` from `AdmissionControlledToolCallback`-wrapped
  method callbacks, with no transport coupling, so capability profiles,
  input validation, structural SQL validation, the schema allowlist,
  read-only enforcement, limits, audit, metrics and tracing apply
  identically to stdio and HTTP.
- **Token values never reach messages or logs.** Both configuration
  exceptions interpolate only the property name or the token *length*, and
  dedicated tests exist to catch a future edit that changes that.

---

## Publication assessment

Within its declared boundary — **opt-in, disabled by default, loopback-only,
token-gated, no TLS of its own, no enterprise authentication** — the
prototype is suitable for publication as part of `1.0.0`.

This is explicitly **not** an assessment that the HTTP transport is
production-ready, safe on a non-loopback interface, or a substitute for a
properly authenticated remote MCP endpoint. Enterprise authentication and
production remote deployment are out of scope for `1.0.0`, and the
documentation says so.

Residual risks accepted for this release: no rate limiting (HTTP-P2-1), no
TLS of its own (mitigated by the loopback bind), and a single shared static
token with no rotation or revocation mechanism.
