# Review: Governed Db2 MCP Gateway — Code Review and Master-Brief Gap Analysis

## Metadata
- **Reviewer role:** ChatGPT architecture and review agent (per ADR 0038's
  cross-model AI engineering workflow).
- **Implementation agent:** Claude Code.
- **Human project owner:** Marco.
- **Status:** Pending repository validation disposition — see
  `docs/reviews/2026-09-01-master-brief-gap-review-response.md` for the
  per-finding classification (`Confirmed` / `Rejected with evidence` /
  `Accepted risk` / `Deferred`) and the source/test evidence backing each
  one.
- **Preservation note:** The content below this line is the review as
  received, unedited. Per ADR 0038, a review input is never silently
  rewritten to look resolved — findings are disposed of in the separate
  response document, not by editing this one.

---

# Governed Db2 MCP Gateway — Code Review and Master-Brief Gap Analysis

**Reviewed artifact:** `db2mcpserverprivate20260901003641Z.zip`
**Archive revision:** `2d1f992fafe73d821de7875db8fff41c0699a0a8`
**Review date:** 2026-09-01
**Scope:** Current private repository implementation compared with `docs/master-brief.md`

## 1. Executive assessment

This is already a substantial implementation, not a scaffold. The archive contains 82 production Java files, 58 test files, 37 ADRs, CI, security documentation, architecture diagrams, a reproducible ITSM demo, observability code, optional Dynatrace export, and an HTTP transport prototype. The package separation, use of outbound ports, contract snapshots, ArchUnit rules, adversarial tests, and explicit documentation of verification limits are particularly strong.

The project is nevertheless **not yet compliant with its own master brief and should not be promoted as the first public release in its current form**. Three release-blocking gaps remain:

1. The schema allowlist does not protect the seven metadata tools. Under the default `METADATA_ONLY` profile, `listSchemas` exposes every non-system schema and the other metadata tools accept arbitrary schema names.
2. Result-size limits are not universal or hard. Metadata results are unbounded; `lookupRefData` lacks cell/payload limits and cannot report truncation; `executeQuery` deliberately allows the first row to exceed the configured payload cap.
3. Sanitization is incomplete. Validation messages can echo SQL and identifiers, the effective-configuration report logs the datasource URL and filesystem path, and the globally enabled DEBUG logger can emit a raw connection/pool exception stack trace.

The right next move is not to add another advanced feature. Close the security and contract gaps already required by the brief, update the stale portfolio documentation, then perform live-Db2 verification.

## 2. Verification performed and limitations

The review inspected source, tests, Maven configuration, CI, application configuration, ADRs, threat model, adversarial catalogue, implementation plan, README, demo assets, and the master brief.

Static inventory:

- 210 files total;
- 82 production Java files;
- 58 test files;
- 226 literal `@Test` methods, plus parameterized/dynamic executions;
- 37 numbered ADRs;
- 11 current MCP tools across all profiles: the original nine plus `getCapabilities` and `explainQuery`, with profile-dependent registration.

`./mvnw -B verify` was attempted. It could not start because this review environment cannot resolve `repo.maven.apache.org`; the failure occurred while resolving the Spring Boot parent POM, before compilation. This is an environment/network limitation, **not evidence of a build defect**. The archive contains no `target/` output with which to independently confirm the claimed CI result, and it contains no `.git` directory, so this review also cannot verify Git history, tags, or history-wide secret scanning.

## 3. Findings

### P0-1 — Metadata tools bypass the configured visibility scope

**Master-brief requirement:** §6.1 requires an allowlist of visible schemas for `metadata-only`; §7.3 requires rejection outside allowed schemas and tables; §7.4 requires metadata enumeration to be capped; §19 requires schema/table allowlists to work.

**Evidence:**

- `Db2MetadataAdapter.listSchemas()` executes `LIST_SCHEMAS_SQL` directly.
- `listTables`, `describeTable`, `getTableDependencies`, `listProcedures`, `describeProcedure`, and `getProcedureSource` accept caller-supplied schema names without calling `SchemaAllowlistValidator`.
- `Db2MetadataAdapter.instrumented()` explicitly documents that the seven metadata methods are not scope-gated.
- `McpConfig` registers all seven metadata tools in every capability profile.
- `LIST_SCHEMAS_SQL` only excludes names beginning with `SYS` plus `NULLID` and `SQLJ`; it does not intersect results with `db2mcp.allowed-schemas`.

**Impact:** With the default `METADATA_ONLY` profile and even with an empty `allowed-schemas` list, a client can enumerate all non-system schemas and query catalog metadata or procedure source for any supplied schema. The public-default profile therefore does not implement the brief's stated visibility boundary.

**Required remediation:**

- Apply one centralized metadata-scope policy to all metadata methods.
- Make `listSchemas` return only configured/authorized schemas.
- Validate schema scope before every metadata query.
- Add an optional table/object allowlist or an equivalent object-scope policy; no table allowlist exists today.
- Treat procedure-source access separately if necessary; "approved procedure source" should not automatically mean every procedure in every visible schema.
- Add adversarial tests for metadata enumeration outside the allowlist, not only `executeQuery` and `lookupRefData`.

### P0-2 — Configured byte limits are not hard and do not cover all data-returning tools

**Master-brief requirement:** §5.1 requires deterministic, bounded outputs and explicit pagination; §6.2 and §7.3 require row and payload limits; §7.4 requires metadata enumeration caps; §19 requires deterministic limits.

**Evidence:**

- In `Db2QueryAdapter`, the payload check is skipped for the first row (`if (!rows.isEmpty() ...)`), explicitly allowing one row to exceed `maxPayloadBytes`.
- The byte count excludes response metadata, column descriptors, JSON quoting/escaping, brackets, commas, field names, and the truncation suffix. It is therefore an estimate of cell content, not a bound on the serialized MCP payload.
- `Db2ValueNormalizer.boundedString()` appends a suffix after truncating to `maxCellBytes`; the returned cell can exceed the configured cell limit.
- CLOB handling uses a character budget, not a UTF-8 byte budget, and documents that non-ASCII output can exceed the limit.
- BLOB/unsupported-type marker strings are not themselves bounded by `maxCellBytes`.
- `lookupRefData` applies only safe-profile row count and timeout. It has no cell or payload limit and returns a plain `List`, so the caller cannot know that rows were truncated.
- The seven pure metadata methods have neither row limit nor query timeout. `getProcedureSource` can return an unbounded source body.

**Impact:** The claimed `maxPayloadBytes`/`maxCellBytes` guarantees are weaker than their names and README wording imply. Large catalog results, procedure sources, reference-data values, or a single oversized query row can exceed the configured boundary and consume excessive memory or LLM context.

**Required remediation:**

- Define whether limits apply to normalized data bytes or the final serialized response; name and document them accordingly.
- For a hard payload guarantee, serialize into a bounded buffer or use a deterministic size model that includes the complete response envelope.
- Never exempt the first row from a hard limit. Return a stable `RESULT_LIMIT_EXCEEDED` error or a bounded/redacted first row.
- Use bounded result envelopes for every list/data tool, including `items`, `returnedCount`, `truncated`, and optional continuation semantics.
- Apply time, row, cell, and payload limits to metadata and procedure-source operations as appropriate.
- Add tests that assert final serialized size, multi-byte content, suffix overhead, metadata enumeration, and oversized procedure source.

### P0-3 — Sensitive-detail sanitization is incomplete

**Master-brief requirement:** §5.1 and §5.3 prohibit sensitive implementation details in errors; §7.4 prohibits logging credentials or connection strings; §9 requires telemetry privacy; §19 requires leakage checks.

**Evidence:**

- `SqlStructuralValidator` includes `JSQLParserException.getMessage()` in an `IllegalArgumentException`; `Db2QueryAdapter` forwards that message into the client-visible `McpToolException`.
- `SchemaAllowlistValidator` includes the caller's full table reference in the rejection message; `validateSchema` includes the supplied schema; `Db2MetadataAdapter.requireIdentifier` includes the supplied identifier. Those messages are forwarded to the MCP client.
- `describeProcedure`/`getProcedureSource` include schema and specific procedure name in client-visible not-found errors.
- `EffectiveConfigReporter` logs `spring.datasource.url`, `allowedSchemas`, and the audit filesystem path. Credential redaction does not remove internal hostname, port, database name, schema names, or filesystem topology.
- `Db2ConnectivityChecker` logs a raw exception at DEBUG, while `logback-spring.xml` sets the entire project package to DEBUG unconditionally. Consequently, the "detail" stack trace is enabled by default and may contain URLs, hosts, driver details, or paths.
- General and audit logs are key/value text, not the operational structured-JSON profile required by §9.1.

**Impact:** An adversarial request or failed connection can cause internal identifiers, SQL fragments/literals, topology, or stack information to cross a boundary that the project claims is sanitized.

**Required remediation:**

- Map validator failures to stable, generic client messages; keep detailed parser diagnostics only in an explicitly local diagnostic channel after redaction.
- Never forward `e.getMessage()` from parsers or generic runtime exceptions to MCP clients.
- Remove the datasource URL, audit path, and raw allowlist from the default effective-config report, or report only safe categories/fingerprints.
- Default project logging to INFO. Provide an explicit local diagnostic profile for DEBUG and document that it may contain implementation detail.
- Add tests with sentinel schema/table names, SQL literals, hostnames, paths, and parser errors across client errors, stderr, audit, metrics, and spans.

### P1-1 — `lookupRefData` does not honor `ADVANCED_QUERY` effective limits

`AdvancedQueryLimitsProperties` states that it applies to `executeQuery/lookupRefData`, and `getCapabilities` reports advanced limits when `ADVANCED_QUERY` is active. `Db2MetadataAdapter`, however, receives only `QueryLimitsProperties` and always applies the safe-query row limit and timeout. It also lacks advanced cell/payload limits entirely.

This creates a concrete disagreement between the capability manifest and actual enforcement. Resolve limits once through the same `ResolvedQueryLimits` abstraction used by `Db2QueryAdapter` and `Db2CapabilityAdapter`, then share that result with `lookupRefData`.

### P1-2 — Tool input contracts and Bean Validation are not implemented as specified

The output side uses named records and both input/output snapshots exist. The input side still exposes raw method parameters (`String schema`, `String table`, `String sql`, etc.) rather than named request records, and no tool method uses Bean Validation constraints. `spring-boot-starter-validation` currently protects configuration properties, not MCP tool arguments.

This leaves framework binding errors, missing/blank values, maximum input length, and cross-field rules outside the common structured error model. Introduce named request DTOs/records, validation constraints, a uniform validation-to-error mapper, and input-size caps. Add read-only/non-destructive tool annotations if the pinned MCP/Spring AI version supports them.

### P1-3 — Traceability and observability are present but not end-to-end complete

Strong foundations exist: per-call UUIDs, audit events, Micrometer counters/timers, span trees, rolling audit logs, optional Dynatrace export, and a startup connectivity check.

Remaining gaps against §§8–9:

- successful responses do not contain the correlation ID; only errors expose it;
- spans are not tagged with the audit correlation ID, and audit events do not contain trace/span IDs, so audit-to-trace navigation is not deterministic;
- audit events omit contract version, database profile identifier, payload-size category, cancellation, and explicit redaction details;
- `mcp.result.bytes` is missing;
- timeout/cancellation counters are not explicit;
- a local default metrics export path is not demonstrated; the local logging exporter covers spans, while Dynatrace is opt-in;
- the health/readiness implementation is a one-time advisory startup log, not a queryable current readiness signal;
- no operational JSON logging profile, example dashboard JSON, or trace screenshot was found.

Use a request-context object created once at the MCP boundary and propagated through policy, adapters, audit, metrics, and spans. This will also prevent independent layers from generating unrelated correlation IDs.

### P1-4 — `explainQuery` needs an explicit exception to the "read-only" story

`EXPLAIN PLAN ...` populates `EXPLAIN_STATEMENT` and `EXPLAIN_OPERATOR`. That is a database write side effect and requires privileges beyond a user with only catalog and `SELECT` privileges, even though it does not execute the explained statement's business logic. The current Javadoc calls those tables "catalog tables" and simultaneously says the operation does not weaken least privilege.

In addition, `ExplainPlanResult` returns the complete original `statementText`, which can carry literals or sensitive SQL and conflicts with the default SQL-fingerprint/redaction direction.

Because this is private-only advanced research, it need not be removed. It does need:

- a dedicated explain-enabled DB role/profile with narrowly documented privileges;
- isolation/cleanup/retention rules for explain rows;
- an honest statement that this capability is not strictly database-read-only;
- a redacted/fingerprinted response by default instead of echoing raw SQL;
- live-Db2 concurrency and privilege tests before promotion.

### P1-5 — Several planned quality gates are absent

Present: CI, Maven Wrapper, JaCoCo policy thresholds, ArchUnit, contract snapshots, gitleaks, Dependabot, smoke tests.

Missing from master brief §11 or §19:

- Maven Enforcer for Java/Maven/dependency convergence constraints;
- deterministic formatter or style gate (Spotless/Checkstyle);
- static analysis (SpotBugs/selected PMD) if retained as a brief requirement;
- a dependency-vulnerability scan that gates CI. Dependabot is an update workflow, not proof that a dependency scan passed;
- performance baseline for representative operations;
- tagged release/public release evidence (expected to remain open while no public repo exists).

The project should either implement the selected gates or update the master brief/ADR to record a deliberate rejection. Leaving requirements silently unimplemented creates false completeness.

### P1-6 — README and security documentation are stale relative to the code

The archive has 11 total tools and 37 ADRs. The README still claims ten tools and 29 ADRs, says there is no query-complexity analyzer, and describes "seven of ten" Db2-dependent tools. The adversarial catalogue still lists the old `EXPLAIN_TIME` correlation race as open even though the current implementation and implementation plan say it was replaced by `QUERYTAG`. The README's claim that traceability uses "never raw SQL" is also too broad while `ExplainPlanResult.statementText` returns raw SQL and validation errors may echo parser content.

Before portfolio promotion, regenerate or manually reconcile:

- tool/ADR counts;
- honest limitations;
- advanced-query and HTTP/Dynatrace status;
- threat-model residual risks;
- adversarial catalogue status;
- the exact difference between tested, H2-verified, live-Db2-verified, and proposed capabilities.

### P1-7 — Live-Db2 evidence remains incomplete for core catalog behavior

The project is unusually honest about H2 versus Db2 verification, which is a strength. Nevertheless, much of the catalog adapter remains dependent on Db2-specific `SYSCAT` semantics. A single `Db2LiveConnectivityIT` is not sufficient evidence for all metadata queries, overloads, foreign-key resolution, procedure source, value normalization, timeouts, and explain-table behavior.

Before public promotion, run a live-Db2 suite that provisions the demo schema and verifies every curated tool end-to-end. Keep advanced/private experiments in a separate test tier if they require broader privileges.

### P2-1 — Column-level policy is not implemented

The brief calls for denied/redacted columns and consideration of classification tags. There is no column denylist, redaction policy, or classification metadata. This is not necessarily required for the first minimal portfolio release if explicitly descoped, but it must not remain an implied capability.

### P2-2 — Cancellation is not a complete feature

JDBC query timeout and bounded shutdown exist, but there is no clear MCP-request cancellation path that calls `Statement.cancel()` or records a cancellation outcome. The brief asks for cancellation where the driver supports it and cancellation metrics. Either implement and test it or mark it as deferred.

### P2-3 — HTTP transport remains correctly private-only

The HTTP prototype is opt-in and documented as proposed. It still has no TLS, failed-auth rate limit, per-client identity/authorization, token rotation strategy, or HTTP-session shutdown drain guarantee. These are known gaps and do not block the stdio public subset, but they do block promotion of HTTP as a production-oriented public capability.

## 4. Master-brief implementation matrix

| Area | Status | Review conclusion |
|---|---|---|
| Java 21 / Spring / Maven foundation | Mostly implemented | Strong structure; build could not be independently rerun because Maven Central was unreachable. |
| Layer separation and dependency inversion | Implemented | Ports, domain types, adapter separation, and ArchUnit evidence are strong. |
| Original nine curated tools | Implemented in code | Behavior still needs metadata scoping, universal bounds, typed requests, and live-Db2 evidence. |
| Capability profiles | Partially implemented | Registration gates work; metadata visibility scope does not, and `lookupRefData` advanced limits disagree with the manifest. |
| Three-layer read-only model | Partially implemented | Query application guard and Hikari intent exist; DB grants are deployment evidence, while `explainQuery` introduces a controlled write side effect. |
| Structural SQL validation | Implemented with documented residual risks | Good fail-closed parser approach and adversarial coverage; error sanitization and breadth/view/UDF residual risks remain. |
| Schema/table/object scope | Incomplete | Query schema allowlist exists; metadata bypasses it; no table allowlist or column policy exists. |
| Time/row/byte/concurrency limits | Partially implemented | Query and concurrency controls exist, but hard serialized-size guarantees and metadata/reference-data bounds do not. |
| Structured errors | Partially implemented | Stable JSON-shaped `McpToolException` exists, but framework/validation paths are not unified and some messages are not sanitized. |
| Contract tests | Mostly implemented | Input and output snapshots exist; named request records, error/pagination semantics, and successful response metadata are incomplete. |
| Audit / metrics / traces | Partially implemented | Strong base, but correlation-to-trace linking, result bytes, cancellation, JSON logs, ongoing readiness, and portfolio evidence are incomplete. |
| Configuration / manageability | Partially implemented | Externalization and validation are good; effective-config output is not topology-safe and environment profiles are absent. |
| CI and security gates | Partially implemented | Tests, JaCoCo, ArchUnit, gitleaks, Dependabot, smoke tests present; dependency scan, Enforcer, formatter/static-analysis decisions remain. |
| ITSM demo and architecture docs | Implemented | Good assets and diagrams; requires live-Db2 execution evidence and stale-doc cleanup. |
| AI-development governance | Implemented | One of the strongest portfolio elements. |
| Portability | Architecture-ready only | Fake adapter proves inversion; no Oracle/PostgreSQL production adapter exists, which the docs correctly avoid claiming. |
| Private advanced research | Broadly implemented | Explain, complexity, Dynatrace, HTTP exist; several remain proposed/private and must not be mixed into initial public claims. |
| Public repository / tagged public release | Not implemented by design | Correct at this stage; create only after the curated release baseline passes the blockers above. |

## 5. Recommended implementation order

### Release blockers

1. Enforce schema/object scope across every metadata tool and add metadata adversarial tests.
2. Introduce bounded response envelopes and hard, testable limits for metadata, reference data, query results, and procedure source.
3. Close error/log/config leakage paths and add sentinel-based leakage tests.
4. Correct `lookupRefData` effective-limit resolution.

### Contract and observability completion

5. Introduce named validated request contracts and a uniform error mapper.
6. Create one request context containing correlation ID, trace context, profile, contract version, and safe database profile ID.
7. Add success response metadata, audit/trace linkage, result-byte and timeout/cancellation metrics, and an operational JSON logging profile.
8. Add a queryable readiness signal or clearly documented stdio operational equivalent.

### Evidence and portfolio cleanup

9. Add a real dependency scan and record decisions on Enforcer/format/static-analysis gates.
10. Run the full curated-tool suite against a provisioned live Db2 instance.
11. Reconcile README, adversarial catalogue, threat model, ADR status, tool counts, and known limitations.
12. Select the public subset. Keep `advanced-query`, `explainQuery`, Dynatrace, and HTTP private unless separately approved for promotion.

## 6. Suggested instruction to Claude

> Treat this review as a gap-validation input, not as an instruction to rewrite the project. First reproduce each finding against the current branch and mark it Confirmed, Rejected with evidence, or Accepted Risk. Prioritize P0-1 through P0-3 before adding new capabilities. For every confirmed finding, update implementation, adversarial tests, contract snapshots where applicable, ADR/implementation-plan status, and the README claims in the same change. Do not promote the HTTP or explain-plan prototypes into the future public baseline merely because code exists; promotion requires a separate security and portfolio decision.

## 7. Overall verdict

**Engineering maturity:** strong for a private AI-assisted laboratory.
**Master-brief completeness:** substantial but incomplete.
**Ready for continued private development:** yes.
**Ready to form the curated public baseline today:** no.

The project already demonstrates far more architectural and engineering substance than a typical portfolio MCP server. Its main risk now is not lack of features; it is that the implementation and README overstate a few governance guarantees that are only partially enforced. Closing those gaps will increase portfolio value more than another advanced capability.
