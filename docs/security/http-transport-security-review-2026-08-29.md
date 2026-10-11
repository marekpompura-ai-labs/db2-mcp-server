# HTTP Transport Prototype — Security Self-Review (thread 6, ADR 0037)

**Review date:** 2026-08-29
**Reviewed artifact:** `configuration/http/*` (`HttpTransportConfig`, `BearerTokenAuthenticationFilter`,
`HttpTransportProperties`, `HttpTransportPropertiesValidator`, `HttpTransportConfigurationException`),
`StdioLifecycleConfig`'s new `@ConditionalOnProperty` guard, `application.yml`'s
`db2mcp.http.*`/`spring.main.web-application-type`/`server.*` keys, `pom.xml`'s
new `spring-boot-starter-tomcat`/`spring-web` dependencies.
**Review type:** Self-review, prep material for the "dedicated security review"
`docs/research/phase5-roadmap.md` §6 requires before ADR 0037 can be promoted.
**Not a substitute for that review** — this is one person's (this session's)
pass, done by re-reading the code adversarially and empirically probing a
running instance, not an independent second reviewer.

## 1. Executive summary

Thread 6 is well-verified at the level its own test suite exercises (a real
embedded Tomcat, a real MCP SDK client, a real handshake and tool call, a
proven leak-detection test). This review went one level further — probing a
running packaged jar with plain `curl` against paths its test suite never
tries — and found **one real, concrete gap the existing tests and ADR 0037
did not catch**: the bearer-token filter does not actually apply to every
path, contradicting its own code comment. Everything else below is either
already documented in ADR 0037/`docs/security/threat-model.md` (repeated
here for completeness, not re-litigated) or a smaller, newly-noted gap.

**Update, same day**: SR-001 and SR-002 were both fixed and verified
before this document was finalized — see the "Fixed" notes on each finding
below and ADR 0037's own "A real gap found by self-review" section for the
implementation detail. This document is left in its original
as-discovered form (not rewritten as if the gaps never existed), with
fix status layered on top, matching this project's established practice
of recording what was actually found before it was fixed, not just the
end state.

## 2. Review scope and method

- Read `BearerTokenAuthenticationFilter`, `HttpTransportConfig`,
  `HttpTransportProperties`, `HttpTransportPropertiesValidator` fresh,
  adversarially — not trusting the ADR's own description of what the code
  does, re-deriving it from the code itself.
- Built the packaged jar (`mvn -DskipTests package`, already current from
  thread 6's own verification pass) and ran it directly with
  `db2mcp.http.enabled=true`, probing with `curl` against paths the
  existing test suite (`HttpTransportIntegrationTest`) never tries — `/`,
  `/actuator/health`, `/favicon.ico`, `/mcp/subpath`, `/mcp/` — with and
  without a valid token, cross-checking against the filter's own log line
  to determine definitively whether it ran at all for each path.
- Did not attempt: fuzzing, a real penetration-test-style exercise, or
  anything requiring tooling beyond `curl`/log inspection. This is a
  focused code + black-box probe review, not a full pentest.

## 3. Severity model

Same as `docs/security/code-review-2026-08-14.md`:

| Priority | Meaning |
|---|---|
| P0 | Release blocker; security boundary or material data-protection failure |
| P1 | Must be resolved before promoting ADR 0037 out of `Proposed` |
| P2 | Important hardening improvement |
| P3 | Documentation/maintainability improvement |

## 4. Findings

### SR-001 — The bearer-token filter does not actually cover every path, contradicting its own documented intent

**Status: Fixed, 2026-08-29, same day.** `HttpTransportConfig` gained a
fourth bean, `fallbackNotFoundServletRegistration` — a catch-all
`HttpServlet` also mapped to `/*`, so every path resolves to *some*
servlet and the filter chain always runs (`/mcp/*` remains more specific
and still wins for its own paths). Verified by re-running the exact `curl`
probe that found the gap against the rebuilt jar: all previously-bypassed
paths now correctly return 401 with the filter's rejection log line
firing. New regression test:
`HttpTransportIntegrationTest.rawHttpClient_hittingAnUnmappedPath_isStillRejectedByTheFilter_notSilentlyBypassed`.
See ADR 0037's Decision section for the fix's own writeup.

**Priority:** P1
**Area:** Authentication / defense-in-depth
**Affected code:** `HttpTransportConfig.bearerTokenFilterRegistration`,
`BearerTokenAuthenticationFilter`'s class javadoc ("Fail-closed on every
path this app exposes, not just `/mcp`")

**What was found**: `FilterRegistrationBean.addUrlPatterns("/*")` registers
a servlet-container filter mapping, but this is a well-known, real servlet-
container behavior (confirmed here specifically in embedded Tomcat 11, not
assumed from general knowledge): **a `/*`-mapped filter only actually runs
for a request that resolves to *some* registered servlet.** If no servlet
mapping matches a path at all, Tomcat's request-mapping short-circuits to
its own stock 404 response, bypassing the filter chain entirely — the
filter's `doFilter` method is never invoked.

**Confirmed empirically, not assumed**: running the packaged jar with
`db2mcp.http.enabled=true` and probing:

| Path | No token | Filter's own log line fired? |
|---|---|---|
| `/mcp` (the one registered servlet) | `401` | **Yes** |
| `/mcp/subpath` (matches `/mcp/*`) | `401` | **Yes** |
| `/mcp/` (matches `/mcp/*`) | `401` | **Yes** |
| `/` | `404` (Tomcat's stock page) | **No** |
| `/actuator/health` | `404` (Tomcat's stock page) | **No** |
| `/favicon.ico` | `404` (Tomcat's stock page) | **No** |

Confirmed via `grep -c "Rejected unauthenticated" <stderr>` after each
probe batch: the count matched exactly the number of `/mcp`-prefixed
requests, zero for the others — not an inference from status codes alone.

**Current actual impact: low.** No other servlet is registered in this
application — `spring-webmvc` is deliberately absent (ADR 0037's own
design), so Actuator's web endpoints never self-register regardless of
`WebApplicationType`, and there is no `DispatcherServlet`/MVC controller
anywhere. The unmapped paths genuinely have nothing behind them to leak
today; the 404 they return is Tomcat's own generic page, not applica­tion
data. **This is not currently an exploitable data-exposure path.**

**Why it's still P1, not P3**: the code's own comment states an intent
("fail-closed on every path... the filter should never rely on that
staying true") that the actual behavior does not deliver. If any future
change adds a second servlet or endpoint — a health-check servlet, a
metrics endpoint, an error-page handler, a dependency bump that
self-registers something — it would be reachable **without the bearer
token**, silently, because nothing today verifies the filter's coverage is
actually total. The gap between documented intent and actual behavior is
exactly the kind of thing a security review exists to catch before it's
load-bearing.

**Suggested fix** (not applied in this review — reporting, not
remediating): register an explicit catch-all `HttpServlet` (a fallback
returning 404) mapped to `/*`, so *some* servlet always matches and the
filter chain is guaranteed to run for every path, not just `/mcp/*`. This
is the standard, well-known fix for this exact servlet-container behavior.
Would need a new test asserting the filter's log line fires for an
arbitrary unmapped path (the gap this review's own empirical check found —
`HttpTransportIntegrationTest` never tried a path other than `/mcp`).

### SR-002 — No minimum length/entropy enforced on the configured bearer token

**Status: Fixed, 2026-08-29, same day.** `HttpTransportPropertiesValidator`
now fails startup if the configured token is under 16 characters,
interpolating only the length (a non-sensitive integer) into the
exception message, never the value - same shape as the existing
blank-token check. New regression test:
`HttpTransportPropertiesValidatorTest.run_throws_whenBearerTokenIsTooShort`.

**Priority:** P2
**Area:** Authentication strength
**Affected code:** `HttpTransportPropertiesValidator.run`

`HttpTransportPropertiesValidator` only checks `bearerToken().isBlank()`.
An operator could set `DB2MCP_HTTP_BEARER_TOKEN=1` (a single character) and
the server would start and accept it as a valid credential — the
constant-time comparison in `BearerTokenAuthenticationFilter` is only
meaningful if the token itself has real entropy; a 1-character token is
trivially guessable regardless of how carefully it's compared.
`config/.env.example`'s own comment recommends `openssl rand -hex 32`, but
nothing enforces that recommendation. Suggest: a minimum-length check (e.g.
16+ characters) in the validator, following the same fail-fast-at-startup
pattern already used for the blank-token and wrong-web-application-type
checks.

### SR-003 — Positive findings worth recording, not gaps

Confirmed by re-reading the code adversarially, not just accepting the
existing tests' framing:

- **No timing oracle between missing/malformed/wrong token** — confirmed
  directly: `isAuthorized` returns `false` uniformly for all three cases,
  and the caller writes an identical 401/body regardless of which branch
  was hit.
- **DNS-rebinding-resistant by construction** — the filter checks a secret
  bearer token, not the `Host` header or request origin, so a DNS-
  rebinding attack (resolving an external domain to `127.0.0.1` after an
  initial check) gains nothing: the attacker still doesn't know the token.
  Worth noting explicitly since `server.address: 127.0.0.1` alone would
  *not* defend against this class of attack on its own — the token is
  doing the real work here, the loopback bind is a second, independent
  layer.
- **No implicit-credential (cookie-based) CSRF surface** — auth is a
  manually-attached `Authorization` header, not a cookie; a malicious
  webpage's `fetch()` call cannot attach a header it doesn't already know,
  unlike cookie-based auth, which browsers attach automatically
  cross-origin. A page probing `127.0.0.1:8080/mcp` from another origin
  can at most confirm *something* is listening (a generic local-service-
  fingerprinting risk common to any locally-bound server, not specific to
  this design) — it cannot act as an authenticated caller.
- **Disabled-by-default path is genuinely inert, not just behaviorally
  quiet** — `HttpTransportDisabledIntegrationTest` already proves the
  `ApplicationContext` itself isn't a `WebApplicationContext` when
  disabled; re-confirmed here doesn't change that finding, just credits it
  as real, not aspirational.

## 5. Not independently re-verified in this pass (carried forward from ADR 0037/threat-model.md, not re-litigated)

Already documented, still open, still accurate as far as this review
found:

- No TLS built in (loopback bind is the stated mitigation for a bare local
  run).
- No rate limiting on failed bearer-token attempts.
- No per-client authorization (one token, full server-wide access).
- No MCP-session-level shutdown-drain guarantee (ADR 0031's guarantee
  doesn't carry over to HTTP).
- Real third-party MCP client interop (Claude Desktop/Code, etc.) —
  `docs/demo/guided-scenarios.md`'s own open checklist item.

## 6. Recommendation

~~Do not promote ADR 0037 to `Accepted` until **SR-001** is fixed and
covered by a test that would have caught it (an unmapped-path probe
asserting the filter's rejection log line fires, not just a status-code
assertion). SR-002 is a reasonable hardening item for the same pass, not a
blocker on its own.~~ **Both fixed same-day** (see the Status notes on
SR-001/SR-002 above) — the fix and its regression test are already in
place, verified against a rebuilt packaged jar with the exact probe that
found the original gap. Everything in §5 ("not independently re-verified
in this pass") is already honestly documented as an open gap in
`docs/security/threat-model.md` and doesn't need to block anything on its
own — it needs a human decision about acceptable risk for a prototype,
which is exactly what the roadmap's "dedicated security review" gate is
for. This document, having now found and closed two real gaps in its own
first pass, stands as one data point that the review process itself works
— not a substitute for the independent human pass the roadmap still
requires before promotion.
