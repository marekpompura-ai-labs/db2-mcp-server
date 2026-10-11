# Governed Db2 MCP Gateway — code re-review after remediation

**Date:** 2026-09-17  
**Reviewed input:** `db2mcpserverprivate20260917084024Z.zip`, diff since 2026-09-09, and supplied manifest  
**Review basis:** findings in `docs/reviews/2026-09-09-chatgpt-code-rereview.md` and the current master brief  

## Executive verdict

The remediation is substantial and technically credible. The new payload-budget implementation measures complete application result objects, fails closed, exposes truncation for list tools, and fixes the previous fail-open serializer. The live-Db2 workflow now runs the complete Maven lifecycle, refuses zero discovered tests, retains Failsafe reports, and uses a versioned Db2 image. `QueryResult` is now deeply immutable. These are real fixes, not documentation-only closure.

However, the repository must **not yet state that all findings from 2026-09-09 are fixed**. Four previously reported findings remain materially unchanged: the cancellation race, validation failures outside the common trace/audit path, absence of a table/object allowlist, and `listSchemas` filtering after the JDBC cap. In addition, the word **universal** remains too strong for the new payload guarantee because `getCapabilities` bypasses it and the measured application object is not proven to equal the complete MCP wire response.

No new critical vulnerability or path to write-capable SQL was found in the reviewed changes.

## Disposition of the 2026-09-09 findings

| Previous finding | Current assessment | Evidence |
|---|---|---|
| RR-P0-1 hard universal payload bound | **Substantially fixed, but not universal** | `PayloadBudget` now measures full application result records and fails closed; `BoundedList` exposes truncation. `getCapabilities` and the MCP protocol wrapper remain outside the invariant. |
| RR-P1-1 live-Db2 workflow | **Fixed by implementation; execution evidence still required** | Workflow runs `./mvnw -B verify -DfailIfNoTests=true`, uploads Failsafe reports, and pins Db2 to `12.1.5.0`. |
| RR-P1-2 cancellation marker race | **Open** | `statement.cancel()` still precedes `cancelled.add(statement)`. |
| RR-P1-3 validation outside audit/trace | **Open** | `ToolInputValidator` still creates an isolated UUID and throws before adapter instrumentation; only `@NotBlank` is applied. |
| RR-P1-4 table/object allowlist | **Open** | Policy configuration still contains only `allowedSchemas`, while the master brief still requires schema/table allowlists. |
| RR-P2-1 deep immutability | **Fixed** | Each inner query row is copied and wrapped before the outer list is copied. |
| RR-P2-2 `listSchemas` cap-before-filter | **Open** | Catalog query is capped by the shared `JdbcTemplate`, then filtered in Java. |

## Findings

### R2-P1-1 — The payload bound is application-level, not yet universal

`PayloadBudget` is a good replacement for `JsonPayloadSizer`: it measures the fully assembled Java return object and fails closed. It correctly covers `executeQuery`, `explainQuery`, list-producing metadata tools, `lookupRefData`, `describeProcedure`, and `getProcedureSource`.

Two boundaries remain outside the guarantee:

1. `Db2CapabilityAdapter.capabilities()` returns `CapabilityManifest` without calling `PayloadBudget` (`Db2CapabilityAdapter.java:109-123`). It also does not record `mcp.result.bytes`.
2. Tests serialize the Java result directly with a local Jackson `ObjectMapper`. They do not measure the actual `tools/call` JSON-RPC response emitted by Spring AI (`result.content`, possible text escaping/structured-content fields, JSON-RPC envelope, request ID and protocol metadata). Therefore ADR 0045's wording that the measurement covers “what actually crosses the boundary” is not demonstrated.

**Impact:** the data-bearing tools now have a genuine application-result bound, which closes the main resource-risk defect. But `maxPayloadBytes` is not proven to cap every tool response or the bytes written to the MCP transport.

**Recommendation:** choose and document one precise contract:

- either rename/define the setting as an **application result budget**, include `getCapabilities`, and explicitly exclude transport framing; or
- enforce a final wire-level limit at the MCP callback/transport boundary and add an end-to-end test measuring the emitted JSON-RPC line.

Until then, describe RR-P0-1 as “fixed for all data-bearing application results, transport overhead excluded,” not “hard universal payload bound.”

### R2-P1-2 — `listSchemas` still caps unauthorized rows before filtering

`Db2MetadataAdapter` configures its catalog `JdbcTemplate` with `maxRows + 1` (`Db2MetadataAdapter.java:237-239`). `listSchemas()` queries all non-system schemas, then filters the resulting list through `schemaAllowlistValidator.isAllowed` (`Db2MetadataAdapter.java:254-266`).

If enough disallowed schemas sort before an allowed schema, JDBC removes the allowed row before authorization filtering. The existing tests use only three rows and a 1,000-row cap, so they cannot expose the defect.

**Recommendation:** push the allowed schema set into the SQL predicate, or use an uncapped/dedicated query and apply authorization before the authorized-result cap. Add a test with `maxRows=1`, several disallowed schemas sorting first, and an allowed schema sorting last.

### R2-P1-3 — Cancellation classification race remains unchanged

`ActiveStatementRegistry.cancelAll()` still executes:

```java
statement.cancel();
cancelled.add(statement);
```

The query thread can receive the cancellation exception and call `wasCancelled(stmt)` before the marker is visible, classifying the event as an ordinary execution failure. It can also deregister before the marker is added, leaving a stale marker.

**Recommendation:** mark before cancellation, roll the marker back if `cancel()` fails, define the deregistration ordering, and add a deterministic latch/barrier concurrency test.

### R2-P1-4 — Tool-boundary validation is still disconnected from traceability

`ToolInputValidator.validate()` still generates a new UUID and throws without emitting an audit event, metric or span (`ToolInputValidator.java:46-53`). The caller therefore receives a correlation ID that cannot be found in server-side evidence.

The request records still use only `@NotBlank`: SQL and identifiers have no maximum input sizes, and `LookupRefDataRequest` does not enforce the `filterColumn`/`filterValue` pair invariant.

**Recommendation:** create correlation context before validation and route rejection through the common outcome recorder. Add conservative `@Size` limits and cross-field validation for the optional filter pair.

### R2-P1-5 — The required table/object allowlist is still absent

The master brief still requires schema and table allowlists (`docs/master-brief.md:476, 1247`), but `QueryPolicyProperties` still contains only `allowedSchemas`. Any object in an allowed schema remains in scope.

**Recommendation:** implement an optional fail-closed table/object policy consistently across parsed SQL, metadata tools, reference-data lookup and explain, or revise the master brief and explicitly accept schema-wide exposure. The combined schema/table requirement cannot be labelled closed while only schema scope exists.

### R2-P1-6 — The security-scan baseline can miss a replacement finding

The new Find Security Bugs workflow compares only the **total count** against a baseline of five. If one known finding disappears and one new vulnerability appears, the total remains five and the workflow reports “At or below the known baseline.” It does not compare detector, class, method or source location.

The scan is also report-only and never fails when the count exceeds five. That can be a valid human-governed policy, but it is not an automated promotion gate and does not by itself satisfy a claim that security scanning must pass before promotion.

**Recommendation:** check in a reviewed SARIF/XML baseline and diff finding identities, or fail on every finding not present in the approved baseline. If the workflow remains report-only, require and record a human acknowledgement on the promotion PR rather than presenting the job itself as a gate.

### R2-P1-7 — `getCapabilities` does not match the repository's traceability claim

`Db2CapabilityAdapter` creates a correlation ID and writes it to the audit event, but does not:

- tag the root span with `correlationId`;
- return the correlation ID to the client;
- record `mcp.result.bytes`.

This contradicts README's statement that all eleven tools receive the same traceability treatment and ADR 0041's statement that every root span is correlation-tagged.

**Recommendation:** apply the same response measurement and correlation convention to `getCapabilities`, or narrow the documentation so it states the exact exceptions.

### R2-P2-1 — Exact-boundary case can report false truncation

When a complete result with `truncated=false` exceeds the budget by exactly the one byte difference between JSON `false` and `true`, `PayloadBudget.fit()` can retry the same full element list with `truncated=true`, fit it, and return every item while claiming truncation (`PayloadBudget.java:71-94`).

**Recommendation:** if no earlier truncation occurred and all elements are retained, do not use `truncated=true` merely to save one byte. Drop at least one element or reject the response. Add a test whose budget sits exactly between those two serialized forms.

### R2-P2-2 — Procedure parameters can be silently truncated

`describeProcedure()` uses the shared capped `JdbcTemplate` for `PROCEDURE_PARAMS_SQL` (`Db2MetadataAdapter.java:307-317`) but returns `ProcedureDetail`, which has no truncation indicator. A procedure with more than `maxRows + 1` parameters receives an incomplete parameter list with no client-visible signal.

This is unusual in real Db2 schemas but violates the project's deterministic-truncation contract.

**Recommendation:** use a dedicated query strategy for the single identified procedure, reject an over-limit parameter set, or return an envelope that can report truncation.

### R2-P2-3 — README evidence is stale

The repository contains 46 ADR files, while README still states “44 ADRs” (`README.md:273`). README also says all eleven tools share the same traceability, which is not true for `getCapabilities` as described above.

## Verified improvements

- `PayloadBudget` replaces fail-open sizing with complete application-object serialization and fail-closed behavior.
- `BoundedList<T>` provides `items`, `rowCount`, `truncated`, and `correlationId` for list results.
- Payload enforcement now covers columns/envelopes, Unicode/escaping, empty results, oversized first items, procedure source and explain statement text at the application-object level.
- Metadata row limits read one extra row so list truncation can be reported.
- Live-Db2 CI uses the full `verify` lifecycle and `failIfNoTests`.
- Failsafe reports are retained as CI artifacts.
- The Db2 container moved from `latest` to version `12.1.5.0` and gained an explicit health check plus provisioning retries.
- `QueryResult` now performs a null-safe deep copy of row lists.
- The MCP output snapshot was updated for the intentional `BoundedList` contract change.
- ADR 0045 records the payload decision and its contract-breaking consequences clearly.
- Find Security Bugs adds useful source-security evidence, despite the baseline-comparison weakness above.

## Verification limits

The supplied ZIP passed archive-integrity validation and the complete supplied diff was inspected. `./mvnw -B verify -DskipITs` could not resolve the Spring Boot parent because this review environment cannot resolve Maven Central (`Unknown host repo.maven.apache.org`). This is an environment/network limitation, not evidence of a project build failure.

The live-Db2 workflow correction is therefore verified by code inspection, not by observing a successful GitHub Actions run. Closure should include a successful workflow URL/run ID and retained Failsafe reports showing the expected live test classes and non-zero test count.

## Recommended remediation order

1. Fix the cancellation race.
2. Bring validation rejection into common correlation/audit/trace handling and add input-size constraints.
3. Decide and implement or formally descope the table/object allowlist.
4. Fix `listSchemas` authorization-before-cap ordering.
5. Define the payload budget boundary precisely; include `getCapabilities` and add a real MCP-wire-size test if the guarantee is transport-level.
6. Replace the security finding count with identity-based baseline comparison.
7. Fix the smaller false-truncation, procedure-parameter and README issues.

## Release recommendation

The current state is suitable for continued private development and demonstrates strong engineering progress. It is **not yet accurate to state that the 2026-09-09 review is fully closed** or that all master-brief security acceptance criteria are met. Once the four carried-open P1 findings are resolved or explicitly accepted by decision, and the successful live-Db2 CI evidence is retained, the project will be close to a defensible public portfolio baseline.
