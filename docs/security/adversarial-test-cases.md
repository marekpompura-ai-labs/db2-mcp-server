# Adversarial test catalogue

Maps the master brief's adversarial test list (§7.6) to actual automated
tests in this repository, not just to a description of intended behavior.
Where a gap was found while building this catalogue, it's recorded here
as found — not silently closed after the fact, and not left undocumented
either. This document is the source of truth for "is X actually tested,"
referenced from `docs/implementation-plan.md`.

Threat model context (brief §7.1): the LLM/MCP client is not a trusted
security principal. Every row below assumes the input could be
adversarially crafted, not just malformed by accident.

| # | Scenario (brief §7.6) | Enforced by | Test evidence | Status |
|---|---|---|---|---|
| 1 | Write statement submitted directly | Regex prefix guard (ADR 0002) + `SqlStructuralValidator` (ADR 0007) | `Db2QueryAdapterTest.requireReadOnlyStatement_rejectsAnythingElse`, `SqlStructuralValidatorTest.validate_rejectsNonSelectStatements` | Covered |
| 2 | Write statement hidden behind comments or whitespace | `SqlStructuralValidator` (structural parse, not text matching) | `SqlStructuralValidatorTest.validate_rejectsWriteHiddenBehindComment` | Covered |
| 3 | Multiple statements | Regex semicolon check (ADR 0002) **and**, independently, `SqlStructuralValidator` (ADR 0007) | `Db2QueryAdapterTest` semicolon cases; `SqlStructuralValidatorTest.validate_rejectsMultipleStatements_independentlyOfRegexGuard` | **Covered, after fixing a real gap** — see below |
| 4 | `SELECT` using `FINAL TABLE`/`OLD TABLE`/`NEW TABLE` | `SqlStructuralValidator` — DB2-specific syntax JSqlParser's ANSI grammar doesn't recognize, fails to parse, fail-closed | `SqlStructuralValidatorTest.validate_rejectsDb2DataChangeTableReferences` | Covered — first time this was actually exercised, not just assumed |
| 5 | Access to a non-allowlisted schema | `SchemaAllowlistValidator` (ADR 0008) | `SchemaAllowlistValidatorTest.validate_rejectsReferenceOutsideAllowedSchema`; `Db2QueryAdapterIntegrationTest.executeQuery_rejectsRealTableOutsideAllowedSchema` (real H2 table, not fictitious) | Covered |
| 6 | Quoted or case-manipulated identifiers | `SchemaAllowlistValidator` normalizes case and strips quotes before matching | `SchemaAllowlistValidatorTest.validate_acceptsReferencesInAllowedSchema` (legitimate use), `.validate_rejectsQuotedOrCaseManipulatedDisallowedSchema` (evasion attempt) | Covered |
| 7 | Procedure invocation | `SqlStructuralValidator` — `CALL` is not a `Select` | `SqlStructuralValidatorTest.validate_rejectsNonSelectStatements` (`CALL` case) | Covered |
| 8 | Enumerate system catalogs outside policy | `SchemaAllowlistValidator` — `SYSCAT`/`SYSIBM` are not in the configured allowlist | `SchemaAllowlistValidatorTest.validate_rejectsSystemCatalogEnumeration` | Covered |
| 9 | Huge result extraction | Row cap (`QueryLimitsProperties.maxRows`, default 1000) at the JDBC level via `Statement.setMaxRows` | `Db2QueryAdapterIntegrationTest.executeQuery_truncatesAtRowCapAgainstRealResultSet` — real 1001-row `ResultSet`, not mocked | Covered |
| 10 | Query timeout | `Statement.setQueryTimeout` via `QueryLimitsProperties.timeoutSeconds` | `Db2QueryAdapterIntegrationTest.executeQuery_timesOutOnExpensiveCrossJoin` — real timeout against a genuinely expensive query, 1s configured limit | Covered |
| 11 | Large cross join or excessive nesting | `SqlComplexityValidator` (ADR 0034) — whole-statement-tree walk; cross joins rejected unconditionally, join count and subquery nesting depth capped per profile | `SqlComplexityValidatorTest` — cross-join rejection (comma-join and explicit `CROSS JOIN`, both profiles), join-count/nesting-depth caps, and the regression-proving case of a cross join and an over-cap join count hidden inside a `WHERE EXISTS`/`IN` subquery (a FROM-only implementation would have wrongly accepted these) | **Covered** — see `docs/adr/0034-query-complexity-controls.md` |
| 12 | Sensitive value leakage through errors | `Db2QueryAdapter` **and `Db2MetadataAdapter`** catch `DataAccessException` and throw `McpToolException` (ADR 0018, ADR 0020) — a stable `ErrorCategory`, a generic message, and SQLSTATE only, never the raw driver message or a stack trace | `Db2QueryAdapterIntegrationTest.executeQuery_sanitizesRealDatabaseErrors`; `Db2MetadataAdapterExceptionSanitizationTest` — real H2 catalog-lookup error, asserts the raw error text does *not* appear and the category correctly falls through to `DB2_EXECUTION_FAILED` | **Covered, after fixing a real gap twice** — see below |
| 13 | Sensitive value leakage through logs, metrics, traces, or audit | Audit: `AuditLogger` writes `PolicyDecisionReason` + `SqlFingerprint`, never raw SQL or row data. Metrics/traces: `MetricsRecorder`/`SpanRecorder`'s only tag values are fixed strings, enum names (`PolicyDecisionReason`, `CapabilityProfile`), and SQLSTATE — a closed vocabulary with no code path that could pass raw SQL or row data, verified directly against `MetricsRecorder.java`/`Db2QueryAdapter.java`'s span-tagging calls (ADR 0010/0011/0012, built in Phase 4). Telemetry-*export* specifically: `EffectiveConfigReporter` is never wired to `DynatraceExportProperties` at all (exclusion by construction), and the Dynatrace export path itself has dedicated leak tests (ADR 0036) | `Db2QueryAdapterIntegrationTest.executeQuery_writesRealAuditEventOnExecutionFailure` asserts the raw failing column name does *not* appear in the audit log. Metrics/span *tag vocabulary* leakage still has no dedicated test — ruled out by the tag vocabulary being closed by construction, confirmed by reading the code. The telemetry-*export* path now does have dedicated tests, specific to the one credential this thread introduced: `DynatraceObservabilityConfigTest.neitherExporter_everLogsTheApiToken` (confirmed to genuinely detect a leak, not just assumed to) and `DynatraceObservabilityIntegrationTest.apiTokenNeverAppearsInApplicationLogsDuringARealExportedCall` (same property, real Spring wiring) | **Covered for audit** (tested); **covered for metrics/traces by construction, not by a dedicated test** for the tag-vocabulary gap; **covered by a dedicated test** for the Dynatrace API token specifically (ADR 0036) — the first credential this project has ever exported telemetry data alongside |
| 14 | Prompt-injection scenario asking the model to bypass policy | All of the above — the validator chain is structural, not semantic; natural-language framing has no effect on it | `SqlStructuralValidatorTest.validate_rejectsPromptInjectionStyleInput_notValidSql` (non-SQL text, rejected) and `.validate_acceptsPromptInjectionNeutralizedByComment` (injected text lands in a SQL comment, becomes inert, correctly accepted as the harmless `SELECT 1` it actually is) | Covered |
| 15 | Row-data extraction through a tool other than `executeQuery` | `lookupRefData` now requires `SAFE_QUERY`/`ADVANCED_QUERY` (invisible under `METADATA_ONLY`, ADR 0006) and enforces the same schema allowlist as `executeQuery` (ADR 0020) | `McpConfigTest.metadataOnly_hidesLookupRefDataAndExecuteQuery`; `Db2MetadataAdapterLookupRefDataIntegrationTest.lookupRefData_rejectsUnderMetadataOnlyProfile` / `.lookupRefData_rejectsSchemaOutsideAllowlist` | **Covered, after fixing a real gap** — see below |
| 16 | Oversized single row/cell extraction (a huge CLOB/BLOB defeating the row cap) | `maxCellBytes`/`maxPayloadBytes` bound individual cells and total response size independently of `maxRows`; unrecognized driver types become a safe marker, never a raw `toString()` (ADR 0023) | `Db2QueryAdapterIntegrationTest.executeQuery_boundsAnOversizedClobCell` / `.executeQuery_omitsBlobContentEntirely` / `.executeQuery_truncatesWhenPayloadBudgetExceededBeforeRowCap` / `.executeQuery_unsupportedDriverTypeBecomesASafeMarker`; `Db2ValueNormalizerTest` (UTF-8 truncation boundary) | **Covered, after fixing a real gap** — see below |
| 17 | Structurally tautological `ON` clause used to defeat cross-join rejection (e.g. `JOIN ... ON 1=1`) | `SqlComplexityValidator.isTautological` (ADR 0035) — rejects unconditionally, same categorical placement as the cross-join check it sits beside | `SqlComplexityValidatorTest.validate_rejectsTautologicalOnPredicate` (and the `ADVANCED_QUERY` / AND-decomposition variants); `Db2QueryAdapterIntegrationTest.executeQuery_rejectsTautologicalOnClause` (real H2, end-to-end) | **Covered** — see `docs/adr/0035-tool-execution-admission-control.md` |
| 18 | Piped SQL syntax (e.g. `FROM t \|> CROSS JOIN ...`) bypassing structural validation, the schema allowlist, and complexity limits simultaneously | `SqlStructuralValidator` and `SqlComplexityValidator` both explicitly reject `instanceof FromQuery` (ADR 0035) — confirmed by direct JSqlParser 5.3 verification that this shape parses as a `Select` yet is invisible to `TablesNamesFinder` and to `SqlComplexityValidator`'s own walker | `SqlStructuralValidatorTest.validate_rejectsPipedSqlSyntax`; `SqlComplexityValidatorTest.validate_rejectsPipedSqlSyntax` | **Covered** — see `docs/adr/0035-tool-execution-admission-control.md` |
| 19 | Unauthenticated or wrong-token access to the opt-in HTTP transport (ADR 0037), **including paths other than `/mcp`**; the bearer token leaking through a rejection response or a startup-failure message; a trivially short/guessable configured token | `BearerTokenAuthenticationFilter` rejects every request without the exact configured token (401, identical response regardless of failure reason, constant-time comparison) — a fallback catch-all servlet guarantees the filter engages for every path, not just `/mcp` (SR-001, found and fixed same-day by `docs/security/http-transport-security-review-2026-08-29.md`); `HttpTransportPropertiesValidator`'s exception message interpolates only the property name/token length, never the token value, and enforces a 16-character minimum token length (SR-002, same review) | `BearerTokenAuthenticationFilterTest` (missing/malformed/wrong-token rejection, and `rejectionResponse_neverContainsTheConfiguredTokenSubstring`); `HttpTransportPropertiesValidatorTest.run_exceptionMessage_neverContainsTheBearerTokenValue` and `.run_throws_whenBearerTokenIsTooShort`; `HttpTransportIntegrationTest` (real embedded Tomcat, real MCP SDK client, raw-HTTP, full-handshake, and unmapped-path rejection paths — the last being the SR-001 regression test) — all confirmed to genuinely catch a reintroduced leak or bypass, not just assumed to (ADR 0037's Verification section) | **Covered for the transport itself, including the SR-001 self-review finding; the broader HTTP threat surface (no TLS, no rate limiting, no per-client authorization) is a documented, not-yet-closed gap** — see `docs/security/threat-model.md`'s HTTP transport subsection and `docs/adr/0037-http-transport-and-authentication-prototype.md` |
| 20 | Metadata enumeration/description outside the configured schema allowlist (`listTables`, `describeTable`, `getTableDependencies`, `listProcedures`, `describeProcedure`, `getProcedureSource` under the default `METADATA_ONLY` profile; `listSchemas` returning non-allowlisted schemas) | `Db2MetadataAdapter.instrumented`'s schema-scope overload calls `SchemaAllowlistValidator.validateSchema` before any of the six methods above ever runs; `listSchemas` filters its result through `SchemaAllowlistValidator.isAllowed` (P0-1, 2026-09-01 master-brief gap review, ADR 0039) | `Db2MetadataAdapterSchemaScopeTest` (11 cases: each method rejected for a schema outside the allowlist before touching DB2/H2, empty-allowlist fail-closed default, `listSchemas` filtering against a wider synthetic catalog) | **Covered, after fixing a real gap** — see below |
| 21 | Sensitive value leakage through *validator-rejection* messages specifically (distinct from item 12's `DataAccessException` handling) — a malformed-SQL parse error, a schema/table-reference rejection, or an identifier-validation failure echoing the caller's own SQL fragment, parser-internal diagnostic text, or attempted schema/table name back into the client-visible error | Every validator-failure catch in `Db2QueryAdapter`'s and `Db2MetadataAdapter`'s guard chains now throws a stable, generic `McpToolException` message instead of forwarding the validator's own `e.getMessage()` (P0-3, 2026-09-01 master-brief gap review, ADR 0039) | `Db2QueryAdapterIntegrationTest.executeQuery_parseFailureMessageNeverContainsTheRawSqlOrParserDetail` / `.executeQuery_schemaRejectionMessageNeverContainsTheAttemptedTableReference`; `Db2MetadataAdapterSchemaScopeTest.rejectionMessage_neverContainsTheRawSchemaNameOrValidatorInternals`; `Db2MetadataAdapterLookupRefDataIntegrationTest.lookupRefData_identifierRejectionMessageNeverContainsTheRawIdentifier` — each a sentinel-token-based test | **Covered, after fixing a real gap** — see below |

## Real gaps found while building this catalogue

Writing tests aimed specifically at each scenario — rather than trusting
that existing tests happened to cover it — surfaced two things that
weren't true before this pass, despite being claimed or assumed
elsewhere:

1. **`SqlStructuralValidator` was not actually an independent layer
   against multi-statement input.** ADR 0007 described it as a second,
   independent check alongside the regex semicolon guard. In fact
   `CCJSqlParserUtil.parse(String)` silently parses only the first
   statement in a multi-statement string and ignores the rest — the
   regex guard in `Db2QueryAdapter` was doing all of the actual
   rejection work for this scenario. Fixed by switching to
   `CCJSqlParserUtil.parseStatements(String)`, which sees the whole
   input and is checked for exactly one statement. Now genuinely
   independent, verified by a test that calls the validator directly,
   bypassing `Db2QueryAdapter`'s guard entirely.
2. **Errors were not sanitized at all before this pass.** A real DB2/H2
   execution error (e.g. an unknown column) would have propagated its
   raw driver message straight to the MCP client — a violation of brief
   §5.1/§11.4 ("sensitive implementation details must not leak through
   errors"). Fixed by catching `DataAccessException` in
   `Db2QueryAdapter.executeQuery` and returning only a generic message
   plus SQLSTATE (a standard code, not sensitive) — the original
   exception's message and stack trace are not chained onto what's
   thrown, so nothing richer can leak through a later serialization
   path either. At the time this was written, this was a minimal fix,
   not the full structured error model the brief describes (§11.4's
   `VALIDATION_FAILED`/`POLICY_REJECTED`/etc. categories) — that gap
   has since been closed by ADR 0018 (`ErrorCategory`/
   `McpToolException`), which every rejection and failure path in
   `Db2QueryAdapter` now throws.

Also as part of this pass: `QueryLimitsProperties` externalizes
`maxRows`/`timeoutSeconds` (previously hardcoded constants, flagged as
follow-up work in ADR 0007) — this is what made a real, fast (~1 second)
timeout test possible at all.

## Real gaps found by external code review (2026-08-14)

Different provenance from the section above: these two were not found
while building this catalogue internally, but by an independent code
review of a code-only snapshot of this repository. Both were verified
against the actual code before being accepted as real, not taken on the
reviewer's word — see ADR 0020 for the full verification trail.

1. **`lookupRefData` bypassed the capability profile entirely.** It was
   registered in the same tool object as read-only metadata tools
   (`SchemaTools`), so it stayed visible and callable under
   `METADATA_ONLY` despite returning actual row values, not metadata. Its
   only check was an identifier-character allowlist — no schema
   allowlist, no row cap, no timeout, no audit/metrics/trace. Confirmed
   directly: `McpConfig` registered `schemaTools` unconditionally, and
   `Db2MetadataAdapter.lookupRefData` built its SQL from caller-supplied
   identifiers with no `SchemaAllowlistValidator` call anywhere in the
   method. Fixed by extracting it into its own conditionally-registered
   tool class (`ReferenceDataTools`) and giving it the same
   capability/scope/row-limit/audit treatment `executeQuery` already had.
2. **Every metadata tool propagated raw driver exceptions.** Unlike
   `Db2QueryAdapter` (gap #2 above, fixed earlier), `Db2MetadataAdapter`
   had zero exception handling anywhere in the file — confirmed by
   grepping the file for `catch`, which matched nothing outside the
   static row-mapper methods' `throws SQLException` declarations. A
   `DataAccessException` from any of the seven catalog-backed methods
   would reach the MCP client with whatever message the JDBC driver
   produced. Fixed by wrapping every method in the same
   `DataAccessException` → `McpToolException` sanitization `executeQuery`
   already used, via a shared `sanitizeErrors` helper.
3. **A single row could still defeat the row cap.** `maxRows` bounds row
   *count*; nothing bounded the *size* of an individual cell or the total
   response. A query returning one row with a large CLOB/BLOB value could
   still exhaust memory, MCP payload size, or an LLM's context window.
   `ResultSet.getObject()` was also copied straight into the result with
   no normalization, so an unrecognized JDBC/driver type's raw
   `toString()` could have leaked whatever internal detail that type
   chose to include. Fixed by `Db2ValueNormalizer` (ADR 0023):
   `maxCellBytes`/`maxPayloadBytes` bound cells and total response size
   independently of `maxRows`, and every value is normalized through an
   explicit, closed set of safe representations (null/boolean/number
   pass through; string/CLOB/XML/temporal bounded; BLOB/binary
   explicitly omitted, never materialized; anything else becomes a safe
   `<unsupported value type: ...>` marker instead of a trusted
   `toString()`).

## Real gaps found by a second, cross-model code review (2026-09-01)

Different provenance again: found by ChatGPT, per the cross-model review
workflow ADR 0038 formalizes, reviewing a `git archive` snapshot of this
repository against the master brief. All three were independently
reproduced against the actual code before being fixed — see
`docs/reviews/2026-09-01-master-brief-gap-review-response.md` for the
full validation trail and ADR 0039 for the fix.

1. **Six of the seven pure metadata methods had no schema-scope check at
   all.** `listTables`, `describeTable`, `getTableDependencies`,
   `listProcedures`, `describeProcedure`, `getProcedureSource` accepted
   any caller-supplied schema, and `listSchemas` returned every
   non-system schema regardless of `db2mcp.allowed-schemas` — confirmed
   by grepping `SchemaAllowlistValidator`'s call sites, which matched only
   `lookupRefData` and `Db2QueryAdapter`. Under the default
   `METADATA_ONLY` profile, a client could enumerate and describe catalog
   objects in any schema. Fixed by extending `Db2MetadataAdapter
   .instrumented` with a schema-scope check applied to all six methods,
   and filtering `listSchemas`'s result to the allowlist.
2. **Configured byte/row limits were not actually hard.** `executeQuery`
   exempted the first row from its payload cap unconditionally; payload
   accounting summed per-cell content bytes only, ignoring JSON structural
   overhead; `Db2ValueNormalizer`'s truncation suffix was appended *after*
   the `maxCellBytes` budget, so a truncated cell could still exceed its
   own stated limit by the suffix's own length; CLOB truncation budgeted
   by character count, not UTF-8 byte count; `lookupRefData` and the seven
   pure metadata methods had no cell/payload/row/timeout limits at all
   (`getProcedureSource`'s source text fully unbounded). Fixed: the
   first-row exemption removed; payload accounting now uses real
   JSON-serialized byte length (`JsonPayloadSizer`, replaced in #62 by
   `PayloadBudget`, which measures the complete response envelope � see
   [ADR 0045](../adr/0045-hard-universal-payload-bound.md)); the truncation suffix
   now counts against its own budget; CLOB truncation now reads
   `maxCellBytes + 1` characters and applies real UTF-8 byte accounting;
   `lookupRefData` and the metadata methods now have the same limits
   `executeQuery` does.
3. **Validator failures forwarded raw parser/identifier detail.** Every
   validator-failure catch in `Db2QueryAdapter`'s and `Db2MetadataAdapter`'s
   guard chains forwarded `e.getMessage()` straight into the client-visible
   `McpToolException` — `SqlStructuralValidator`/`SqlComplexityValidator`
   embed `JSQLParserException.getMessage()`, `SchemaAllowlistValidator`
   embeds the caller's raw schema/table reference, `requireIdentifier`
   embeds the raw identifier. `EffectiveConfigReporter` also logged the
   full datasource URL, complete schema list, and audit file path at
   startup, and the project's own package logger was unconditionally
   `DEBUG` with no profile guard. Fixed: every validator-failure catch now
   throws a stable, generic message; the effective-config log now reports
   only safe categories/counts; the default log level is now `INFO`,
   matching root.

## Real gaps found while planning thread 4 (performance and red-team testing, ADR 0035)

Different provenance again: found while researching what thread 4's
"red-team" half should actually target, specifically the new surfaces
threads 2-3 (`explainQuery`, `SqlComplexityValidator`) added — not by
building this catalogue from scratch or by external review.

1. **The tautological-`ON` gap (item 17) was found by reading
   `SqlComplexityValidator.visitJoins` adversarially**: it only checked
   `Join.isCross() || Join.isSimple()`, so `JOIN ... ON 1=1` — an
   unrestricted Cartesian product with a normal-looking `ON` clause —
   passed unchecked. Notably, this codebase's own test suite *depended* on
   this gap staying open: `Db2QueryAdapterIntegrationTest`'s cross-join
   timeout test used exactly this shape to slip past the complexity gate
   and reach DB2's JDBC timeout. Fixed by `isTautological` (ADR 0035);
   the timeout test was rewritten to a genuinely non-tautological but
   still fully non-selective predicate (`ON a.N > 0`) instead.
2. **The piped-SQL gap (item 18) was found empirically, not by inspection**,
   while verifying a different, narrower claim (that piped SQL was already
   provably unreachable). Compiling and running JSqlParser 5.3 directly
   against the pinned dependency jar showed `CCJSqlParserUtil.parse("SELECT
   * FROM T |> CROSS JOIN OTHER_SCHEMA.SECRET")` returns a `FromQuery` that
   **is** `instanceof Select` — the opposite of what an existing code
   comment claimed. This bypassed `SqlStructuralValidator` (ADR 0007),
   `SchemaAllowlistValidator` (ADR 0008), and `SqlComplexityValidator` (ADR
   0034) simultaneously, on the branch already pushed to remote at the time
   it was found. Fixed by an explicit `instanceof FromQuery` rejection in
   both validators (ADR 0035).
3. **Master brief section 7.5's "bound concurrent tool executions" was
   declared in the brief and never implemented anywhere in the code** — no
   semaphore, executor cap, or admission gate existed; `InFlightRequestTracker`
   (ADR 0031) only does shutdown-drain bookkeeping, it never throttles. This
   isn't one of the numbered §7.6 adversarial scenarios above (it's an
   availability-protection requirement from §7.5), so it doesn't have its
   own table row, but it's the same class of finding: a documented
   guarantee with nothing behind it. Closed by `ToolExecutionAdmissionGate`/
   `AdmissionControlledToolCallback` (ADR 0035) — every registered tool,
   not just `executeQuery`, is now bounded by
   `db2mcp.concurrency.max-concurrent-tool-executions`.

## Known gaps, not addressed here

- ~~**Query-complexity analysis** (item 11) relies entirely on the
  timeout — there's no dedicated check for join count, nesting depth, or
  estimated cost. A cross join small enough to finish inside the
  timeout but still expensive would not be caught by anything.~~ **Closed
  (ADR 0034)**: `SqlComplexityValidator` rejects cross joins
  unconditionally and caps join count/nesting depth across the whole
  statement tree, independent of the timeout. One narrower gap remains,
  not addressed by this or any client-side check: a scalar UDF that
  itself issues an expensive query inside DB2 is structurally invisible
  to a client-side SQL-text parse — the same category as the view-
  indirection gap below, not something a SQL-text analyzer can see
  through.
- **Metric/trace leakage** (item 13) has no *dedicated test* asserting
  raw SQL or row data can't reach a metric tag or span tag — it's
  currently guaranteed only by the tag vocabulary being closed
  (hardcoded strings and enum names, no free-text field ever populated
  from query content), confirmed by reading `MetricsRecorder.java` and
  `Db2QueryAdapter.java`'s span-tagging calls rather than by a test that
  would fail if that ever changed. A test asserting this structurally
  (e.g. reflectively checking that no `MetricsRecorder`/`SpanRecorder`
  call site ever passes the raw `sql` parameter) would close this gap
  more durably than manual code review.
- ~~**Max payload bytes** (flagged as open in ADR 0007) is still not
  implemented — only row count is capped, not response size.~~ **Closed
  (ADR 0023, CR-005)**: `maxCellBytes`/`maxPayloadBytes` now bound
  individual cells and total response size independently of `maxRows`.
  Values are also normalized (CLOB bounded-read, BLOB explicit omission,
  unrecognized driver types replaced with a safe marker rather than a raw
  `toString()`) — `Db2QueryAdapterIntegrationTest`'s CR-005 tests and
  `Db2ValueNormalizerTest` cover both, against real H2
  `Clob`/`Blob`/`ARRAY` values, not mocks. **Residual gaps in this "closed"
  claim itself found by a later review (P0-2, 2026-09-01) and closed by
  ADR 0039**: the cap wasn't actually hard (a first-row exemption, the
  truncation suffix counted against nothing, CLOB truncation was
  character- not byte-budgeted) — see the 2026-09-01 section above. The
  response envelope's own structural overhead (column descriptors, wrapper
  keys) still isn't included in the byte accounting — a stated, still-open
  residual gap, not silently assumed closed either.
- **View indirection** (flagged in ADR 0008): a view in an allowed
  schema that itself reads a disallowed schema would pass
  `SchemaAllowlistValidator` unnoticed, since the validator only sees
  the queried statement's own text.
- **Tautological-predicate detection only decomposes `AND`, not `OR`**
  (ADR 0035): `ON 1=1 OR c.id=t.id` is genuinely unrestricted (the `OR`
  makes the whole predicate always true) but `isTautological` does not
  detect it — a narrow, deliberate residual gap, not an oversight.
- **The "breadth bomb" gap in `SqlComplexityValidator`**: many sibling
  scalar subqueries or `UNION ALL` branches, each individually within
  `maxNestingDepth`/`maxJoins`, are not capped in aggregate — only the
  running join count and the current nesting depth are checked, never a
  total subquery/branch count. Found while planning thread 4 (ADR 0035)
  but deliberately deferred, not fixed in that pass: closing it risks
  conflicting with `SqlComplexityValidatorTest`'s existing CTE/UNION-
  sibling non-accumulation tests (which deliberately assert siblings do
  *not* accumulate depth), and ADR 0035 already carried three production
  changes — cleaner as its own focused follow-up thread with its own ADR
  than rushed into the same pass.
- ~~**`explainQuery`'s `EXPLAIN_TIME` correlation race** (ADR 0033's own
  admitted gap, revisited but not closed while planning thread 4): the
  read-back query correlates "the plan just captured" via `MAX(EXPLAIN_TIME)`
  across the whole `EXPLAIN_STATEMENT` catalog table, not scoped by
  requester or session. Two concurrent `explainQuery` calls could race,
  with one caller observing another caller's captured plan. This is only
  genuinely testable against live DB2 (ADR 0009) — EXPLAIN tables are
  catalog machinery with no H2 equivalent — so it stays an open, documented
  gap rather than a fabricated test that couldn't actually exercise the
  race.~~ **Closed (live-DB2 verification, 2026-08-30, tracked as issue
  #28)**:
  the first live-DB2 verification pass reproduced this race for real
  (two concurrent `explainQuery` calls did observe each other's captured
  plans), confirming it wasn't just a theoretical gap. Fixed by replacing
  the `MAX(EXPLAIN_TIME)` correlation with an `EXPLAIN PLAN SET QUERYTAG
  = '<correlation-id>'` tag and a composite-key join scoped to that tag
  (`Db2QueryAdapter.captureAndReadPlan`) — no longer testable-only-live,
  it's fixed and the fix itself is live-DB2-verified. See ADR 0033's
  Status section for the full writeup.
- Everything here is H2-verified, not DB2-verified (ADR 0009) — DB2's
  actual parser/optimizer could behave differently for some of these
  cases, particularly `FINAL TABLE`/`OLD TABLE`/`NEW TABLE`, which is
  real DB2 syntax that H2 was never going to execute either way.
> **2026-09-21 update.** The HTTP transport received its dedicated security
> review ([`http-transport-security-review-2026-09-21.md`](http-transport-security-review-2026-09-21.md)).
> Two P1 findings were fixed and regression-tested — validation running
> after the servlet port was bound, and a blank expected token
> authenticating every request. The gaps listed below remain open and
> accepted for the experimental prototype boundary.

- **The HTTP transport prototype's broader threat surface** (ADR 0037,
  opt-in, off by default): no rate limiting on failed bearer-token
  attempts (item 19 above covers the token itself never leaking, not
  brute-force resistance beyond constant-time comparison), no TLS built
  in (loopback-only binding is the actual mitigation for a bare local
  run), no per-client authorization (one token grants full server-wide
  access), and no MCP-session-level drain guarantee on shutdown (the
  HTTP analogue of the bug ADR 0031 fixed for stdio). All four are
  documented, not fixed, in `docs/security/threat-model.md`'s HTTP
  transport subsection and ADR 0037's own Explicitly out of scope
  section. These gaps were why that ADR remained `Proposed`; the dedicated
  review has since completed (2026-09-21) and accepted them as residual
  risks within the experimental prototype boundary, rather than closing
  them.
