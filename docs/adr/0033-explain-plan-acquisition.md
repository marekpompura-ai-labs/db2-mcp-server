# ADR 0033: Explain-plan acquisition via a dedicated ExplainPort

## Status
**Accepted** - implemented, unit-tested, and live-DB2 verified (2026-08-30)
against real DB2 12.1.5.0 CE. All four checklist items pass, including the
concurrent-correlation item, after fixing a real bug the first verification
pass found: the `MAX(EXPLAIN_TIME)` correlation strategy was genuinely
broken under concurrency, exactly the risk this ADR flagged at design time
as "most likely to need rework, not just documentation." See "Live-DB2
verification" below for the full history - both the original failure and
the fix that closed it.

## Date
Proposed 2026-08-26.

## Context
`docs/research/phase5-roadmap.md` sequences thread 2, "explain-plan
acquisition and analysis," as the direct follow-on to thread 1 (ADR 0032,
which gave `ADVANCED_QUERY` its first real behavior but explicitly left
`EffectiveCapabilities.explainPlan` at `false`, "not implemented, stated
here rather than silently omitted"). Master brief §6.3 (verbatim, already
quoted in ADR 0032): "Private experimentation only by default... may
support explain-plan experiments or configurable policy exceptions...
must never weaken database-account least privilege."

DB2 exposes access-plan capture through ordinary JDBC: `EXPLAIN PLAN FOR
<statement>` populates DB2's own `EXPLAIN_STATEMENT`/`EXPLAIN_OPERATOR`
catalog tables (created via IBM's shipped `EXPLAIN.DDL`) without executing
the statement; a second query reads the resulting operator tree back. No
special driver capability is needed beyond issuing that SQL through the
same `JdbcTemplate` `Db2QueryAdapter` already owns.

## Decision drivers
- §6.3's "must never weaken least privilege" is binding, identically to
  ADR 0032: explain-plan capture cannot mean new DB2 grants, and the
  statement being explained must pass the same structural/schema gates
  `executeQuery` enforces - explaining a statement DB2 wouldn't otherwise
  be allowed to run is still a policy exception, not a harmless read.
- ADR 0009 classifies DB2 catalog machinery (`SYSCAT.*` views) as having
  "no substitute that can verify this SQL actually runs correctly" - H2's
  `MODE=DB2` compatibility mode adjusts SQL dialect, not catalog contents,
  so it has no `EXPLAIN_STATEMENT`/`EXPLAIN_OPERATOR` equivalent. EXPLAIN
  tables are squarely in this same category: this is a DB2-only, Tier
  2-only feature, with no honest Tier 1 (H2) equivalent possible.
- Because of the point above, forcing this capability onto the shared
  `QueryPort` (which `FakeDatabaseAdapter` also implements, as the
  project's portability-evidence story, ADR 0029) would require either a
  meaningless stub or an exception-throwing fake - either way, misleading
  the very portability claim `QueryPort`'s dual-adapter contract tests
  exist to prove honestly.
- Explain-plan is explicitly `ADVANCED_QUERY`-only per §6.3, not something
  `SAFE_QUERY` grants (unlike `executeQuery`/`lookupRefData`, which both
  query-capable profiles expose) - the existing `allowsQueryExecution()`-
  based gates (`CapabilityProfile.requireQueryExecutionAllowed`, the
  `McpConfig` row-data conditional) are too broad and needed a narrower
  sibling, not reuse.

## Decision
1. **New `ExplainPort`** (`application/port/out/ExplainPort.java`),
   deliberately separate from `QueryPort` for the reason above - a single
   method, `ExplainPlanResult explainQuery(String sql)`.
2. **`Db2QueryAdapter` implements both `QueryPort` and `ExplainPort`** -
   same physical connection, capability profile, and SQL validators either
   operation needs; a second class would only duplicate that wiring.
   `explainQuery` runs the identical capability/structural/schema gate
   sequence `executeQuery` does (capability check, `requireReadOnlyStatement`,
   `SqlStructuralValidator`, `SchemaAllowlistValidator`), then:
   - `jdbcTemplate.execute("EXPLAIN PLAN SET QUERYTAG = '<tag>' FOR " + sql)`
     - no bind parameter for `sql` itself, since it has already passed the
     same single-statement structural gate `executeQuery`'s own literal-text
     `jdbcTemplate.query(sql, ...)` call already relies on for that
     identical trust boundary. `<tag>` is a per-call identifier derived from
     the tool call's own `correlationId` (server-generated `UUID`, hex/dash
     only - safe to embed directly), truncated to fit `QUERYTAG`'s
     `CHAR(20)` column.
   - A `SELECT` against `EXPLAIN_OPERATOR` joined to `EXPLAIN_STATEMENT` on
     their full natural composite key (`EXPLAIN_REQUESTER`, `EXPLAIN_TIME`,
     `SOURCE_NAME`, `SOURCE_SCHEMA`, `SOURCE_VERSION`, `EXPLAIN_LEVEL`,
     `STMTNO`, `SECTNO`) and filtered on `es.QUERYTAG = '<tag>'`, capped via
     `FETCH FIRST <n> ROWS ONLY`. **Not** the originally-designed `WHERE
     EXPLAIN_TIME = (SELECT MAX(EXPLAIN_TIME) FROM EXPLAIN_STATEMENT)` -
     that database-wide correlation was live-verified broken under
     concurrency (see "Live-DB2 verification" below) and replaced with this
     `QUERYTAG`-based join, which DB2's own `EXPLAIN PLAN` statement syntax
     supports for exactly this purpose.
   - Truncation logic (`Db2QueryAdapter.truncateOperators`, a pure
     function, deliberately extracted so it's the one part of this feature
     testable without any DB2/JDBC involvement) reuses `ResolvedQueryLimits`'
     `maxRows` as the operator-count cap - no new config, since
     `explainQuery` is only reachable when `ADVANCED_QUERY` is already
     active, meaning `maxRows` is already the `AdvancedQueryLimitsProperties`
     value (ADR 0032).
3. **New `CapabilityProfile.requireExplainPlanAllowed(String)`** - narrower
   than `requireQueryExecutionAllowed`: requires `this == ADVANCED_QUERY`
   exactly, not "any query-capable profile."
4. **New `ExplainTools`** MCP tool class (`mcp/tool/ExplainTools.java`),
   registered in `McpConfig` under its own condition
   (`capabilityProfile == CapabilityProfile.ADVANCED_QUERY`), separate
   from the existing `allowsQueryExecution()` block - `explainQuery` is
   invisible under `SAFE_QUERY`, not just rejected when called, matching
   ADR 0006's "invisible, not just rejected" contract.
5. **`Db2CapabilityAdapter`**: `EffectiveCapabilities.explainPlan` is now
   `capabilityProfile == CapabilityProfile.ADVANCED_QUERY`, computed
   alongside (but independently of) `queryCapable` - the first real value
   for a field that has existed, always `false`, since ADR 0028.
6. **New domain types** (`domain/model/`): `ExplainOperator(operatorId,
   operatorType, totalCost)` - one row of `EXPLAIN_OPERATOR`, scoped to
   the "practical minimum" for an operator-level plan (not the full
   `EXPLAIN_ARGUMENT`/`EXPLAIN_OBJECT`/etc. family `EXPLAIN.DDL` also
   creates); `ExplainPlanResult(statementText, operators, truncated)` -
   mirrors `QueryResult`'s own truncation convention.
7. **`recordOutcome` (audit/metrics helper) parametrized on tool name** -
   was hardcoded to `"executeQuery"`; now both `executeQuery` and
   `explainQuery` share it, avoiding a second, duplicated copy.

## Live-DB2 verification (run 2026-08-30, against real DB2 12.1.5.0 CE)
Run against the same local Docker instance (`db2-mcp-local`) used for ADR
0022/0031's live verification, connecting as `db2inst1` (this environment
has no restricted-role user actually provisioned - see the EXPLAIN-grant
finding below). Four open questions from the original version of this
section, resolved:

- **`EXPLAIN.DDL` is pre-provisioned as-is** at `sqllib/misc/EXPLAIN.DDL`
  in this image (DB2 12.1.5.0 CE) - ran cleanly via `db2 -tf
  sqllib/misc/EXPLAIN.DDL` after connecting, no path adjustment needed.
- **Schema targeting**: run without an explicit `SET SCHEMA` first, both
  `EXPLAIN_STATEMENT` and `EXPLAIN_OPERATOR` land in the connecting user's
  own schema (`DB2INST1` here) - confirmed via
  `SYSCAT.TABLES WHERE TABNAME IN ('EXPLAIN_STATEMENT','EXPLAIN_OPERATOR')`.
- **Least-privilege grant gap, confirmed real but untested end-to-end**:
  `docs/demo/itsm-schema.sql`'s `ITSM_MCP_READONLY` role has no `SELECT`
  grant on `EXPLAIN_STATEMENT`/`EXPLAIN_OPERATOR` (confirmed via
  `SYSCAT.ROLEAUTH` - zero rows for that role). This environment has never
  actually provisioned a restricted DB2 user to connect as (only
  `db2inst1`/`db2fenc1` exist, and `ITSM_MCP_READONLY` itself is granted to
  nobody - the `GRANT ROLE ... TO USER` line in `itsm-schema.sql` has been
  commented out since ADR 0022), so this server has only ever been
  live-verified running as the DB2 instance owner (implicit ALL
  privileges), not as a genuinely least-privileged connection. A real
  least-privilege deployment of `explainQuery` needs `GRANT SELECT ON
  EXPLAIN_STATEMENT, EXPLAIN_OPERATOR TO ROLE ITSM_MCP_READONLY` added
  alongside the existing ITSM-table grants - not yet added to
  `itsm-schema.sql`, tracked as a follow-up rather than blocking this
  round of verification (matches this project's existing, already-stated
  limitation, not a new one introduced here). The `QUERYTAG`-based
  correlation fix (below) reads `EXPLAIN_STATEMENT.QUERYTAG` as part of its
  join, same table, so this grant gap covers the fixed version too - no
  new grant surface introduced by the fix.
- Column types/nullability: not separately investigated - `ExplainOperator`
  only reads `OPERATOR_ID`/`OPERATOR_TYPE`/`TOTAL_COST`, all three read back
  correctly typed (int, string, double) in every run below, so this was
  folded into the functional checks rather than inspected in isolation.

Checklist items (`docs/demo/guided-scenarios.md`, "Live-DB2 verification
checklist"):
1. **`getCapabilities.explainPlan` by profile - PASS.** `true` under
   `ADVANCED_QUERY`, `false` under `SAFE_QUERY` (checked directly;
   `METADATA_ONLY` inherits the same `capabilityProfile ==
   ADVANCED_QUERY` check in code, not independently re-verified live).
2. **`explainQuery` single-call happy path - PASS.** `SELECT * FROM
   ITSM.TICKET` under `ADVANCED_QUERY` returns a non-empty `operators`
   list, `truncated: false`, real DB2 cost figures.
3. **Tool visibility by profile - PASS.** `tools/list` returns exactly 10
   tools (no `explainQuery`) under `SAFE_QUERY` and exactly 8 under
   `METADATA_ONLY` - confirms `McpConfig`'s narrower `ADVANCED_QUERY`-only
   gate against real startup, not just `McpConfigTest`'s mocks.
4. **Concurrent-call correlation - originally FAIL, now PASS after a fix.**
   Two `explainQuery` calls issued back-to-back with different SQL (one a
   simple single-table scan, one a two-table join specifically chosen so
   its plan can't collapse to the same shape via join elimination -
   `SELECT * FROM ITSM.TICKET` vs. `SELECT T.TICKET_ID, C.LAST_NAME FROM
   ITSM.TICKET T JOIN ITSM.CUSTOMER C ON T.CUSTOMER_ID = C.CUSTOMER_ID`)
   originally reproducibly cross-contaminated: in 2 of 5 concurrent runs,
   the response for the simple-scan request came back with `statementText`
   correctly echoing `SELECT * FROM ITSM.TICKET` but `operators` containing
   the *other* request's 4-operator join plan (`RETURN`/`HSJOIN`/`TBSCAN`/
   `TBSCAN`) instead of its own true 2-operator plan. Root cause matched
   exactly what this ADR's Decision section already predicted: `WHERE
   EXPLAIN_TIME = (SELECT MAX(EXPLAIN_TIME) FROM EXPLAIN_STATEMENT)` reads
   the single most-recent row in `EXPLAIN_STATEMENT`/`EXPLAIN_OPERATOR`
   database-wide, with no per-call scoping - when two `EXPLAIN PLAN FOR`
   statements race, whichever wrote last "wins" the correlation for both
   callers. (An initial reproduction attempt using a join query that
   selected only the driving table's own FK column looked like the same
   bug but wasn't - DB2 legitimately eliminates that join via the FK
   constraint, collapsing it to the same simple scan shape. Confirmed
   against `EXPLAIN PLAN FOR` run directly via CLP, independent of the
   app, before concluding it was contamination and not just a
   coincidentally-cheap plan.) Filed as
   issue #28.

   **Fixed** by replacing the `MAX(EXPLAIN_TIME)`-based correlation with
   DB2's own `EXPLAIN PLAN SET QUERYTAG = '<tag>' FOR <sql>` clause, tagging
   each call with a unique identifier (derived from its own `correlationId`)
   and reading the operator tree back via a join to `EXPLAIN_STATEMENT` on
   the full natural composite key, filtered on that exact tag - deterministic
   per-call scoping, no timestamp race window at all. Re-verified against
   the same reproduction: **11/11 runs (8 concurrent, 3 spaced) show zero
   cross-contamination** - every response's `operators` always matches its
   own `statementText`, both when only one response survives the
   still-unfixed transport bug (#29, see below) and when both land in the
   same run. See `Db2QueryAdapter.captureAndReadPlan`'s Javadoc for the
   fix's full rationale.

**A second, separate defect surfaced incidentally while reproducing item
4, unrelated to this ADR's own design**: two concurrent calls to the
*same* tool name (reproduced with `explainQuery`, `listSchemas`, and
`getCapabilities` alike - not `explainQuery`-specific) reliably drop one
response with `reactor.core.Exceptions$ErrorCallbackNotImplemented:
java.lang.RuntimeException: Failed to enqueue message` from the MCP SDK's
`StdioServerTransportProvider` - 100% reproduction across 9 runs across
three different tools, confirmed present even with stdin held genuinely
open the whole time (a native `System.Diagnostics.Process`-driven harness,
not the stdin-EOF timing ADR 0031 covers), so this is a distinct
concurrency defect in same-tool-name response delivery, not a shutdown-
timing race. Filed as
#29,
cross-referenced from ADR 0031 rather than folded into this ADR, since it
reproduces with tools that have nothing to do with explain-plan
acquisition.

`docs/demo/guided-scenarios.md`'s checklist item for this ADR has been
updated in place to reflect all four items passing, per this project's
established practice (ADR 0022) of correcting checklist/ADR text in place
rather than filing a silent followup.

## Consequences
- `getCapabilities` now reports genuinely different `explainPlan` values
  by profile - the second real, client-visible signal (after ADR 0032's
  limits) that `ADVANCED_QUERY` means something concrete.
- `SAFE_QUERY`/`METADATA_ONLY` behavior is unchanged; the MCP contract
  snapshots were regenerated to add the 11th tool (`explainQuery`) but
  every other tool's entry is byte-identical to before.
- Query complexity/cost controls (roadmap thread 3) remain unimplemented -
  this ADR does not touch `SqlStructuralValidator`/`SchemaAllowlistValidator`
  at all, reusing them exactly as `executeQuery` already does.
- A real, if narrow, security surface: `EXPLAIN PLAN FOR` still requires
  DB2 to parse and bind the statement's schema/table references, so the
  same schema-allowlist and structural-validation guarantees `executeQuery`
  provides apply here too - verified by this ADR's own gate-rejection unit
  tests (`Db2QueryAdapterExplainQueryTest`), though the DB2-side behavior
  they assume is what live verification confirms.
- The correlation fix (`QUERYTAG`-based join, see "Live-DB2 verification")
  adds no new configuration and no new DB2 grants - `QUERYTAG` is an
  ordinary column already present on `EXPLAIN_STATEMENT` wherever
  `EXPLAIN.DDL` is provisioned, not a project-specific addition.
- **`EXPLAIN PLAN FOR` is a real, documented exception to this project's
  read-only guarantee (ADR 0002), not a harmless read** - flagged
  explicitly here, in README.md, and in `docs/security/threat-model.md`
  per the 2026-09-01 master-brief gap review (P1-4, issue #41). The
  explained statement's own business logic never runs, but the EXPLAIN
  PLAN command itself physically writes plan metadata into DB2's own
  `EXPLAIN_STATEMENT`/`EXPLAIN_OPERATOR` catalog tables - bounded to
  exactly those two tables, never the caller's own schema data. A prior
  version of `Db2QueryAdapter`'s own javadoc said "[n]othing is executed
  for real," which conflated "the caller's SQL never runs" with "no write
  happens at all" - corrected in code and here. `ExplainPlanResult.statementText`
  also returns the caller's own SQL verbatim, unredacted and
  unfingerprinted unlike the audit path - a deliberate decision, not an
  oversight: the tool's whole purpose is showing the caller their own
  query's plan back to them, so there is no confidentiality boundary
  crossed by echoing text they themselves supplied in the same request.

## Verification / enforcement
`./mvnw verify -DskipITs` (231 tests, up from 219): `CapabilityProfileTest`
(new `requireExplainPlanAllowed` cases), `Db2CapabilityAdapterTest`
(`explainPlan` true only under `ADVANCED_QUERY`), `McpConfigTest` (split
into per-profile tests reflecting the new 8/10/11 tool counts),
`Db2QueryAdapterExplainQueryTest` (gate-rejection tests proving
capability/structural/schema failures never touch the `DataSource`, plus
pure-function truncation tests) - deliberately does **not** attempt a
mocked-JDBC "happy path" test for the `EXPLAIN PLAN FOR` + read-back
sequence itself, since per ADR 0009 that would only prove self-consistency
with this code's own assumptions, not that DB2 actually behaves that way.
MCP contract snapshots regenerated and reviewed (11 tools). ArchUnit
confirms no layering violation from the new `ExplainPort`/domain types.

## Related decisions
ADR 0006 (capability-profile two-point enforcement pattern, followed
exactly here with a narrower third gate), ADR 0007 (structural validation,
reused unchanged), ADR 0009 (Tier 1/Tier 2 testing split - the reason this
is Tier 2-only and `ExplainPort` stays separate from `QueryPort`), ADR
0022 (the checklist-and-handoff pattern this ADR's live verification
follows), ADR 0028 (`getCapabilities`/`EffectiveCapabilities.explainPlan`,
the seam this ADR fills in), ADR 0029 (`FakeDatabaseAdapter`/portability
evidence - the reason `ExplainPort` isn't added to it), ADR 0032 (the
`ADVANCED_QUERY` foundation this thread builds on directly).
