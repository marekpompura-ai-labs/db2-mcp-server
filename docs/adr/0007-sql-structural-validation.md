# ADR 0007: Validate SQL structurally and enforce resource limits

## Status
Accepted — implemented and verified in this environment (`mvn test`
green, 51 tests; packaged jar smoke-tested under `SAFE_QUERY`).
Consequences below have been updated with what was actually found,
distinct from what was anticipated when this ADR was written as
`Proposed`.

## Date
Proposed 2026-08-11, Accepted 2026-08-11 (same day — implementation
followed immediately once the library decision was made).

## Context
`executeQuery` (gated behind `SAFE_QUERY`/`ADVANCED_QUERY` since ADR
0006) guarded SQL with a regex: must start with `SELECT` or `WITH`, must
not contain a semicolon (ADR 0002). That guard was already proven
insufficient once by construction — the semicolon check exists only
because a test written specifically to break the prefix-only version
found a real bypass. The master brief (§7.3) calls for structural/AST-
based validation instead of regex, plus resource limits (timeout, max
rows, max payload bytes) that didn't exist at all.

## Decision drivers
- Must reject DDL/DML/DCL, transaction control, `CALL`, and DB2's
  data-change table references (`FINAL TABLE`, `OLD TABLE`, `NEW TABLE`)
  even when embedded inside an otherwise-valid-looking `SELECT`.
- Must fail closed on syntax it doesn't recognize, rather than passing
  unrecognized constructs through.
- Should not require hand-writing a SQL grammar — that's the same class
  of mistake (homemade parsing for a security boundary) that produced
  the semicolon bug.

## Considered options
1. **JSqlParser** — pure Java, Apache-2.0, generic ANSI SQL grammar,
   widely used for exactly this kind of statement-type inspection.
2. **Apache Calcite** — full parser + validator + optimizer framework;
   substantially heavier than needed for a single structural gate.
3. **jOOQ's (open-source) parser** — viable, but less established prior
   art for this specific use case than JSqlParser.
4. **Hand-rolled tokenizer** — no new dependency, but repeats the exact
   risk category that caused the semicolon bug.
5. **Let DB2 itself parse it** (`PREPARE`-only) — rejected outright:
   validates syntax only *at* the database, after the SQL already
   reached it, defeating the point of an application-layer guard.

## Decision
Used **JSqlParser 5.3** (`com.github.jsqlparser:jsqlparser`, confirmed
on Maven Central before adding). `SqlStructuralValidator.validate(sql)`
parses via `CCJSqlParserUtil.parse(sql)` and rejects anything that isn't
an instance of `net.sf.jsqlparser.statement.select.Select` — that type
covers plain `SELECT` and `WITH ... SELECT` (CTEs) alike in this
version's unified statement model, so no separate CTE-detection logic
was needed.

**Layered on top of, not replacing, the existing regex guard** — this
is a change from what was anticipated in the `Proposed` version of this
ADR, which said the parser "removes the regex guard's entire failure
class." On reflection while implementing, replacing a working, tested
guard with a new dependency's behavior is a strictly worse risk profile
than keeping both: `requireReadOnlyStatement` (prefix + semicolon) runs
first as a cheap filter, `SqlStructuralValidator` runs second. Same
defense-in-depth reasoning as ADR 0002's three layers — belt and
suspenders, not sequential replacement.

**Resource limits**, added in the same change: `Db2QueryAdapter` now
builds its own dedicated `JdbcTemplate` (from the injected `DataSource`,
not the shared bean) with `maxRows` and `queryTimeout` set once at
construction — avoiding mutating shared Spring-managed state on every
call. Fetching one row beyond the row cap lets the code detect
truncation without a separate `COUNT(*)` query; `QueryResult` gained a
`truncated` boolean field for this. **Update, later in Phase 3:** these
limits were externalized as `QueryLimitsProperties`
(`db2mcp.query.max-rows`/`db2mcp.query.timeout-seconds`, defaults 1000/
30) — the "not yet externalized" note below is resolved, done as
follow-up work within this same ADR rather than a new one, since it was
already anticipated here.

## Consequences
- **Corrected, not just anticipated:** the original text here claimed
  multi-statement/hidden-write input was "caught by two independent
  mechanisms instead of one." That was **not actually true** until a
  later pass (`docs/security/adversarial-test-cases.md`) built a test
  that called `SqlStructuralValidator` directly, bypassing
  `Db2QueryAdapter`'s regex guard — and found
  `CCJSqlParserUtil.parse(String)` silently parses only the first
  statement of `"SELECT 1; DROP TABLE X"` and ignores the rest. The
  regex guard was doing all of the actual rejection work for this
  scenario; the parser layer was not independent at all, despite the
  claim. Fixed by switching to `CCJSqlParserUtil.parseStatements(String)`
  and checking for exactly one statement. This is exactly the kind of
  claim the adversarial catalogue exists to pressure-test — an ADR
  saying "two independent layers" is not evidence that both layers were
  ever actually exercised independently.
- **Real finding during implementation:** `CCJSqlParserUtil.parse("")`
  returns `null` rather than throwing `JSQLParserException` — an empty
  or blank statement would have thrown an unhandled
  `NullPointerException` instead of the intended `IllegalArgumentException`
  if not caught. Found by a unit test aimed specifically at this edge
  case (`validate_rejectsEmptyString`), fixed with an explicit null
  check. This is the same category of "test the guard, not just the
  happy path" discipline that caught the semicolon bug in ADR 0002 —
  worth calling out because it happened again, on a different guard.
- **Real finding, later in Phase 3: errors were not sanitized at all.**
  A real DB2/H2 execution error would have propagated its raw driver
  message straight to the MCP client, violating brief §5.1/§11.4. Fixed
  by catching `DataAccessException` in `executeQuery` and returning
  only a generic message plus SQLSTATE — see
  `docs/security/adversarial-test-cases.md` item 12 for the test and
  full rationale. This wasn't part of the original scope of this ADR
  but landed here since it's the same guard boundary.
- **Anticipated risk, not yet verifiable:** JSqlParser not recognizing
  valid DB2-specific read-only syntax could reject a legitimate query.
  Still true — this can only be checked against a live DB2 instance and
  real queries, which remains unavailable in this environment. One
  specific case (`FINAL TABLE`/`OLD TABLE`/`NEW TABLE`) is now confirmed
  to fail-close as intended, though only against H2, not real DB2 — see
  the adversarial catalogue.
- **Max payload bytes is still not implemented** — only row count is
  capped. Byte-size limiting needs a design decision (per-row estimate?
  streamed cutoff?) not made here; left as explicitly open, not
  silently dropped.
- **Schema/table allowlists are now implemented separately** — see ADR
  0008, which was undesigned work at the time this ADR was first
  written.

## Verification / enforcement
`mvn test` (80 tests total as of the adversarial-catalogue pass)
includes `SqlStructuralValidatorTest`, covering: plain `SELECT`, joins,
`WITH` CTEs, and subqueries as accepted; `DELETE`/`UPDATE`/`INSERT`/
`DROP`/`TRUNCATE`/`CALL`/`MERGE`/`GRANT` as rejected; unparseable
garbage and empty string as rejected (fail-closed); multi-statement
input rejected independently of the regex guard; DB2's `FINAL TABLE`/
`OLD TABLE`/`NEW TABLE` rejected; writes hidden behind comments
rejected; prompt-injection-style input either rejected (not valid SQL)
or correctly accepted when neutralized into an inert comment.
`Db2QueryAdapterIntegrationTest` covers query timeout and error
sanitization against real H2 execution, not mocks. The full mapping
from master brief §7.6 to actual tests, including what's still not
covered, lives in `docs/security/adversarial-test-cases.md` — that
document, not this ADR, is now the source of truth for adversarial
coverage status.

## Related decisions
ADR 0002 (read-only enforcement, layer 3 — this validator is now a
fourth independent check alongside it), ADR 0006 (capability profiles —
this validation only runs when `executeQuery` is reachable at all).
