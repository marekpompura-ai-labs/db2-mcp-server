# Threat model

Scope: the Governed Db2 MCP Server as implemented in this repository -
a stdio-transport MCP server (ADR 0001) giving an LLM/MCP client governed,
read-only access to IBM Db2. This document explains *why* the defenses
in `docs/master-brief.md` §7 exist and what they cover; `docs/security/
adversarial-test-cases.md` is the companion document proving each one is
actually enforced, not just described - this document references that
one by item number rather than duplicating its content.

Stdio remains this server's default and only *supported* transport. An
opt-in HTTP transport prototype also exists (ADR 0037,
`db2mcp.http.enabled`, default `false`), accepted for publication in 1.0.0
**within an experimental prototype boundary only** - not as a production
or remote-deployment surface, per its dedicated review
(`docs/security/http-transport-security-review-2026-09-21.md`) - genuinely different attack
surface once enabled (inbound network reachability), covered separately in
its own subsection below rather than folded into the stdio-default threats
T1-T12, since most of those threats and mitigations are transport-agnostic
(they apply to whatever request reaches `ToolCallback.call(String)`,
regardless of how it arrived) while T10 and the "Out of scope" section
specifically assumed stdio was the *only* transport, an assumption ADR
0037 changes for anyone who opts in.

## Trust boundaries

See `docs/architecture-diagrams.md` §14.1 and §14.4 for the diagrams this
section describes in prose. Two boundaries matter:

1. **MCP client ↔ this server**, over stdio. The client is untrusted
   (see Trust assumptions below) - everything it sends is adversarial
   input until proven otherwise by the guard chain.
2. **This server ↔ Db2**, over JDBC. The Db2 credential and the
   read-only principal it authenticates as are the only things crossing
   this boundary in the outbound direction; result data crosses it
   inbound. Nothing else in the process (no HTTP listener, no JMX, no
   other network-reachable surface - independently reconfirmed via
   `lsof` in ADR 0011, ADR 0012, and ADR 0016) is reachable from outside
   the process at all.

## Trust assumptions (brief §7.1)

Stated plainly because every mitigation below exists specifically because
one of these is assumed true, not as a hedge:

- The LLM can be manipulated by prompt injection.
- The MCP client may submit malformed, adversarial, or unexpectedly
  expensive input.
- SQL text can be obfuscated.
- A prefix check for `SELECT` or `WITH` is insufficient on its own (this
  project found this true in practice, not just in theory - see "A real
  gap this threat model would have missed" below).
- Application controls can contain bugs.
- Observability output can accidentally disclose sensitive information.

## Assets

| Asset | Sensitivity | Where it could leak |
|---|---|---|
| Db2 credentials (`DB2_USER`/`DB2_PASSWORD`) | High - direct database access | Logs, effective-config report, error messages, process environment |
| Row data returned by `executeQuery`/`lookupRefData` | Variable, unknown to this server - it has no column classification | Logs, metrics, traces, audit, error messages |
| Raw SQL text submitted by the client | Medium - can reveal query patterns, occasionally embeds literal sensitive values | Logs, metrics, traces, audit |
| Db2 schema/procedure metadata (via the 7 `SYSCAT`-backed tools) | Low-medium - internal structure, not data | Same channels, plus unrestricted metadata enumeration |
| This server's own process (stdout channel) | High - stdout is the JSON-RPC transport itself (ADR 0001) | Any stray `System.out.println`, misconfigured logger, or library that writes to stdout by default |

## Threats and mitigations

| # | Threat | Mitigation | Decision record | Test evidence |
|---|---|---|---|---|
| T1 | Client sends a write statement (`INSERT`/`UPDATE`/`DELETE`/DDL/DCL) | Rejected by `SqlStructuralValidator` before reaching Db2; Db2-level grants only ever include `SELECT`/`EXECUTE`, so even a bypass couldn't write | ADR 0002, ADR 0007 | Adversarial catalogue #1 |
| T2 | Client hides a write behind comments, whitespace, or multiple statements | Structural (AST) parsing, not text matching; genuinely independent multi-statement rejection via `parseStatements` | ADR 0007 | Adversarial catalogue #2, #3 |
| T3 | Client accesses data outside the schemas this deployment is meant to expose | `SchemaAllowlistValidator`, deny-by-default, normalizes case/quoting before matching | ADR 0008 | Adversarial catalogue #5, #6, #8 |
| T4 | Client extracts unbounded result volume | Row cap (`Statement.setMaxRows`) and JDBC-level statement timeout | ADR 0007 | Adversarial catalogue #9, #10 |
| T5 | Client submits an expensive query (large cross join, deep nesting) that finishes within the timeout but is still costly | Timeout only - no dedicated complexity analyzer | ADR 0007 | **Partially covered** - adversarial catalogue #11, "Known gaps" |
| T6 | A rejected or failed query's error message leaks driver internals, table/column names, or raw SQL to the client | `Db2QueryAdapter` catches `DataAccessException` and every guard-chain rejection, throwing `McpToolException` (a stable `ErrorCategory`, a generic message, and SQLSTATE only); original message/stack trace not chained | ADR 0007, ADR 0018 | Adversarial catalogue #12; verified end-to-end over a real MCP JSON-RPC session, not just a unit test — see ADR 0018 |
| T7 | Db2 credentials leak through the effective-config report, logs, or process output | `EffectiveConfigReporter` is never wired to `spring.datasource.username`/`.password` at all (not "redacted," structurally absent); URL-embedded-credential pattern additionally redacted defensively | ADR 0015 | `EffectiveConfigReporterTest.neverLogsAnyCredentialValueEvenWhenUrlEmbedsOne`; real packaged-jar run with fake credentials, confirmed absent from the entire stderr log |
| T8 | Row data or raw SQL leaks through audit logs, metrics, or trace spans | Audit events carry `PolicyDecisionReason` + `SqlFingerprint` only; metrics/span tags are a closed vocabulary (fixed strings, enum names, SQLSTATE) | ADR 0010, ADR 0011, ADR 0012 | Adversarial catalogue #13 - audit path tested directly; metrics/span path verified by code inspection, not yet a dedicated test (stated as a real gap, not implied closed) |
| T9 | A prompt-injection payload convinces the LLM to ask this server to bypass its own policy | The guard chain is structural, not semantic - natural-language framing embedded in SQL has no special meaning to `SqlStructuralValidator`; the server enforces policy independent of what the LLM intended to ask for | ADR 0007 | Adversarial catalogue #14 |
| T10 | The MCP process itself becomes network-reachable, widening the attack surface beyond stdio | Actuator is metrics-only, no HTTP endpoint; JMX explicitly disabled; a servlet container dependency (`spring-boot-starter-tomcat`) is now always on the classpath (ADR 0037), but `spring.main.web-application-type: none` is the explicit default, so no servlet container starts and no port opens unless `db2mcp.http.enabled` is deliberately flipped on - see the HTTP transport subsection below for the opt-in case | ADR 0001, ADR 0011, ADR 0037 | Re-verified via `lsof` on the packaged jar in ADR 0011, ADR 0012, and ADR 0016 with HTTP disabled (default); `HttpTransportDisabledIntegrationTest` additionally proves the `ApplicationContext` itself is genuinely not a `WebApplicationContext` when disabled, not merely that HTTP beans behave as no-ops |
| T11 | The process is left running (and therefore reachable/attackable) after its MCP client has disconnected | Stdin-EOF-triggered clean shutdown | ADR 0016 | `EofTriggeredShutdownInputStreamTest`; real packaged-jar run confirming exit on stdin EOF |
| T12 | Invalid configuration (e.g. non-positive row limit) causes undefined behavior at query time instead of failing at startup | Bean Validation on all `db2mcp.*` `@ConfigurationProperties`, fails `SpringApplication.run()` with a specific property/value/reason | ADR 0015 | Real packaged-jar run with `DB2MCP_QUERY_MAXROWS=0`, confirmed clean startup failure with the exact offending property named |

## HTTP transport (opt-in, ADR 0037)

Only relevant when `db2mcp.http.enabled=true` - the default configuration
is unaffected and T1-T12 above, plus their mitigations, still apply
unchanged (they're transport-agnostic; nothing about them assumed stdio
specifically). This subsection is additive - a new attack surface that
exists only when explicitly opted into, not a replacement for anything
above.

| # | Threat | Mitigation | Test evidence |
|---|---|---|---|
| H1 | An unauthenticated network caller reaches the MCP endpoint, **or any other path** | `BearerTokenAuthenticationFilter`, a servlet `Filter` guaranteed by spec to run before any MCP/tool logic, rejects every request without a valid bearer token (401). A same-day self-review (SR-001, `docs/security/http-transport-security-review-2026-08-29.md`) found and fixed a real gap here: a `/*`-mapped filter only actually runs for a path that resolves to *some* registered servlet, so with only `/mcp/*` registered, any other path bypassed the filter entirely and hit Tomcat's own 404 instead - fixed by an explicit `fallbackNotFoundServletRegistration` catch-all servlet, so every path now resolves to a servlet and the filter always runs | `BearerTokenAuthenticationFilterTest`; `HttpTransportIntegrationTest`'s raw-`HttpClient`, real-MCP-client, and unmapped-path probes (the last of which is the SR-001 regression test) |
| H2 | The bearer token is guessed via brute force or a timing side-channel on the comparison | Token comparison uses `MessageDigest.isEqual(byte[], byte[])`, not `String.equals` - a constant-time comparison. `HttpTransportPropertiesValidator` fails startup if the configured token is under 16 characters (SR-002, fixed 2026-08-29 - previously only checked for blank), so constant-time comparison is now actually comparing something with a real entropy floor. No rate limiting on failed attempts (see Residual risk) | Implementation guarantee asserted by code review, not test-measured (a timing-based test would be flaky/environment-dependent); `HttpTransportPropertiesValidatorTest.run_throws_whenBearerTokenIsTooShort` covers the length floor |
| H3 | The bearer token leaks through a rejection response, an application log line, or an exception message | Missing/malformed/wrong token rejected identically (no oracle); rejection WARN log carries method/path/remote address only, never the header value; `HttpTransportPropertiesValidator`'s startup-failure message interpolates only the property *name* | `BearerTokenAuthenticationFilterTest.rejectionResponse_neverContainsTheConfiguredTokenSubstring`; `HttpTransportPropertiesValidatorTest.run_exceptionMessage_neverContainsTheBearerTokenValue`; both confirmed to genuinely catch a reintroduced leak, not just assumed to work (ADR 0037's Verification section) |
| H4 | Traffic between the client and this server is intercepted or tampered with (no TLS) | None built into this prototype - `server.address: 127.0.0.1` (loopback-only default) is the actual mitigation for a bare local run; any real deployment must terminate TLS in front (reverse proxy/service mesh) before being reachable beyond loopback | Documented as a hard, explicit caveat in ADR 0037; not testable without a real TLS-terminating proxy in front, out of scope for this pass |
| H5 | A disconnecting HTTP client's in-flight tool call gets its response dropped, the HTTP analogue of the bug ADR 0031 fixed for stdio | Not solved - Spring Boot's generic `server.shutdown: graceful` drains the servlet container's in-flight requests, but has no MCP-session-level guarantee equivalent to ADR 0031's | Known, documented gap (ADR 0037 Explicitly out of scope) - not yet reproduced or fixed |
| H6 | One valid bearer token grants full server-wide access; no way to give different callers different permissions | Not built - `CapabilityProfile` remains one process-wide value; auth here is a binary yes/no gate, not per-client authorization | Known, documented gap (ADR 0037 Explicitly out of scope) |
| H7 | The bearer token can never be rotated without a process restart; if it leaks (logs, a compromised client, an accidental commit) it stays valid indefinitely until an operator notices and manually changes the config value | Not built - `db2mcp.http.bearerToken` is a single static config value with no expiry, versioning, or automated rotation mechanism. An operator *can* rotate manually (change the property, restart the process), but nothing enforces, schedules, or automates it, and no mechanism exists to invalidate a specific leaked token without restarting (which invalidates every client's token simultaneously) | Known, real gap, implied by ADR 0037's single-static-token design but not previously named explicitly anywhere in this file or ADR 0037 itself - surfaced by the 2026-09-01 master-brief gap review (P2-3) and named here per issue #47 |

## Dependency-vulnerability monitoring (P1-5, 2026-09-01 master-brief gap review, issue #42)

Two independent, complementary layers, added the same day for the same
finding - neither alone would have caught the CVEs described below,
which is why both exist rather than picking one:

- **CI-time, per-push/PR**: `ci.yml`'s "Dependency vulnerability scan
  (Trivy, SBOM-based)" step generates a CycloneDX SBOM from the actual
  Maven-resolved dependency tree (`cyclonedx-maven-plugin`, bound to the
  `package` phase) and scans it with Trivy, failing the build on any
  HIGH/CRITICAL finding. Its first real run found 3 CRITICAL CVEs
  (CVE-2026-65182, CVE-2026-65905, CVE-2026-68525) in a transitive
  `tomcat-embed-core:11.0.24`, fixed the same day via a `tomcat.version`
  property override to `11.0.25` in `pom.xml`.
- **Continuous, repo-wide**: GitHub's own Dependabot alerts, enabled at
  the repository's Settings -> Code security page
  (2026-09-07) alongside three prerequisites/complements in the same
  panel:
  - **Dependency graph** - required for everything else on this list;
    was off by default for this private repo.
  - **Automatic dependency submission** ("Enabled", standard GitHub
    runners) - without this, GitHub's dependency graph is built by
    *statically parsing* `pom.xml`, which cannot see a BOM-managed
    transitive version like `tomcat-embed-core`'s (never written
    literally in `pom.xml` at all, resolved only through
    `spring-boot-starter-tomcat` and the Spring Boot BOM) - the same
    class of blind spot that made the CI-side SBOM approach necessary
    in the first place (see this repo's `pom.xml` comment on the
    `cyclonedx-maven-plugin` block, and PR #54's description, for the
    other scanning approaches tried and abandoned for the same
    static-vs-resolved reason). Enabling this makes Dependabot alerts see
    the actual resolved graph, not just what's literally typed in the
    manifest.
  - **Dependabot malware alerts** - a distinct threat class from CVEs
    (known-malicious/typosquatted packages), alert-only, no PRs, enabled
    for the same reason as CVE alerts: free additional coverage with no
    downside.

  **Deliberately left off**: **Dependabot security updates** (and its
  dependent, **Grouped security updates**). This would have Dependabot
  auto-open fix PRs *directly against `main`* -
  `.github/dependabot.yml` sets no `target-branch`, so it defaults to
  the repository's default branch - bypassing the feature-branch to
  `devel` to `main` workflow this project follows for every other
  change, where each promotion gets an explicit human go-ahead.
  Revisit only after adding an explicit `target-branch: devel` to
  `dependabot.yml`. **(Public edition: this reasoning describes the
  private repository's three-branch flow. This public repository is
  single-branch, `dependabot.yml` deliberately sets no `target-branch`,
  and the default branch is therefore the correct target.)** **Dependabot on self-hosted runners** was left off
  too, but for an unrelated reason: this repo's CI only uses
  GitHub-hosted `ubuntu-latest` runners, no self-hosted runner exists
  for the setting to apply to.

**Why both layers are actually necessary, not redundant - the branch
scope differs, not just the trigger:** Dependabot alerts (the
Security-tab feature) is scoped to the repository's **default branch**
(`main`) - GitHub builds the alerting dependency graph from `main`'s
manifests, not from `devel` or any feature branch. A vulnerable
dependency introduced on a feature branch or sitting in `devel` produces
no native Dependabot alert until that code actually reaches `main`. The
CI-time Trivy/SBOM step has the opposite scope: it runs on *every*
push and PR regardless of branch (feature branch, `devel`, or `main`
alike), catching a newly-introduced vulnerable dependency immediately,
before it ever reaches `main` - but only when something pushes; it
can't retroactively flag a dependency that was already fine when last
scanned but has since had a new CVE disclosed against it. So: CI-time
Trivy answers "did this change just introduce something bad, on any
branch," and Dependabot alerts answers "did something already in
`main` become bad later, even without a new push" - genuinely
different questions, both worth answering, neither one a superset of
the other.

## Out of scope (explicit non-goals)

Per `docs/master-brief.md` §15.8's public/private feature matrix - stated
here so "not defended against" isn't confused with "overlooked":

- **Arbitrary writes.** Never, through this gateway, under any profile,
  in either the public or private version - a separate product and its
  own threat model would be required, not a configuration change to this
  one. One narrow, documented exception: `explainQuery` (ADR 0033,
  `ADVANCED_QUERY`-only) never executes the caller's own statement, but
  `EXPLAIN PLAN FOR` is itself a real write - it physically inserts plan
  metadata into DB2's own `EXPLAIN_STATEMENT`/`EXPLAIN_OPERATOR` catalog
  tables. Bounded to exactly those two tables (never the caller's own
  schema data, never anything the client's SQL text could redirect), and
  surfaced explicitly here per the 2026-09-01 master-brief gap review
  (P1-4, issue #41) rather than left as an unstated side effect of "never
  executed."
- **OAuth transport.** Not implemented - the HTTP transport prototype (ADR
  0037) that now exists uses a single static bearer token instead,
  explicitly scoped as too large for a first prototype. Still private-lab-
  only future research if this thread is ever promoted.
- **Production/remote deployment of the HTTP transport.** The dedicated
  security review is complete
  (`docs/security/http-transport-security-review-2026-09-21.md`) and ADR
  0037 is **Accepted — publication appropriate within the stated
  experimental prototype boundary**: opt-in, disabled by default,
  loopback-only, token-gated. That acceptance explicitly does **not**
  cover production readiness, enterprise authentication or remote
  deployment, which remain out of scope.
- **Column-level data classification** (brief §7.4's "consider
  classification tags for columns such as email, personal identifiers").
  Not implemented - this server has no concept of which columns are
  sensitive within an allowed table, so redaction happens at the
  schema/table boundary (ADR 0008), not the column boundary. Explicitly
  descoped for the current phase (2026-09-07, issue #45), not merely
  unimplemented - the 2026-09-01 master-brief gap review flagged this as
  undecided (P2-1), and the human project owner's decision was to defer
  rather than build it now. If revisited, the stated preference is to
  redact matching cells rather than reject queries selecting a
  denylisted column - see the review response doc's P2-1 section for
  the full decision record.
- **Multi-tenant isolation.** One capability profile, one schema
  allowlist, one Db2 principal per running instance - not a shared
  service handling multiple untrusted tenants behind one process.

## Residual risk

Carried forward from `docs/security/adversarial-test-cases.md`'s "Known
gaps" section rather than restated independently, so there's one place
this list can go stale rather than two:

- No dedicated query-complexity analyzer beyond the statement timeout
  (T5 above).
- No maximum response payload byte limit, only a row-count cap - a query
  returning few rows with very wide/large column values isn't bounded by
  `maxRows`.
- Metrics/span leakage (T8's second half) is verified by code inspection
  of a closed tag vocabulary, not by a dedicated test that would fail if
  a future change introduced a free-text tag populated from query
  content.
- Schema allowlist checks table/view names as referenced in SQL; a view
  in an allowed schema that itself selects from a non-allowlisted
  schema's tables is not specially detected (DB2's own view-creation
  privileges are the actual control here, outside this server's reach).
- HTTP transport mode only (H2, H4-H7 above, opt-in, ADR 0037): no rate
  limiting on failed bearer-token attempts; no TLS built in; no
  MCP-session-level drain guarantee on shutdown; one token grants full
  server-wide access with no per-client authorization; that one token
  has no rotation mechanism beyond a manual config-change-and-restart.
  None of these apply when `db2mcp.http.enabled=false` (the default).

## A real gap this threat model would have missed if written from the brief alone

Included deliberately, because a threat model that only lists threats
the brief already named would be less honest than this project's own
practice elsewhere: `docs/security/adversarial-test-cases.md` records
that `SqlStructuralValidator` was, for a period, *not actually* an
independent defense against multi-statement input the way ADR 0007
originally claimed - `CCJSqlParserUtil.parse()` silently parsed only the
first statement of a multi-statement string, so the regex guard in
`Db2QueryAdapter` was doing all the real rejection work for T2. This
threat model's own T2 mitigation description is accurate *because* that
gap was found and fixed, not because the original design was correct
from the start - worth stating plainly rather than presenting the
current mitigation table as if it were right on the first attempt.
