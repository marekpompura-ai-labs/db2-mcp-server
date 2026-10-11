# ADR 0034: Deterministic query complexity limits (cross joins, join count, nesting depth)

## Status
Accepted

## Date
Proposed 2026-08-26, Accepted 2026-08-26.

## Context
`docs/research/phase5-roadmap.md` sequences this as thread 3, right after
thread 1 (ADR 0032, `ADVANCED_QUERY` differentiated limits) and thread 2
(ADR 0033, explain-plan acquisition). Before this ADR, nothing in the
policy chain looked at query *shape* at all: `SqlStructuralValidator`
checks statement type (single `SELECT`/`WITH`, no multiple statements, no
forbidden clauses); `SchemaAllowlistValidator` checks schema
qualification. A query with an unrestricted Cartesian product or
arbitrarily deep subquery nesting passed both gates cleanly.

Master brief §6.2 (`safe-query` profile) lists "Maximum joins, nesting, or
other complexity thresholds if reliably enforceable" as a requirement, and
separately: "Define policy for common table expressions, subqueries,
unions, functions, and special registers." §7.3 gates the whole feature
class on one condition: "Consider query-cost controls only if they are
deterministic enough to explain and test." §7.5 (Abuse and availability
protection) names the concrete shape to reject: "Reject pathological cross
joins or other known expensive shapes where feasible." §7.6's adversarial
test catalogue requires a test for "large cross join or excessive
nesting" — `docs/security/adversarial-test-cases.md` tracked this row as
"Partially covered" (query timeout only, no dedicated analyzer) with an
explicit "Known gaps" entry; this ADR closes that gap.

A structural, whole-tree parse was chosen over a cost estimate specifically
because of §7.3's gate: counting joins and nesting depth from the parsed
AST is deterministic and trivially explainable ("your query has 6 joins,
the limit is 5"); a cardinality- or cost-based estimate would depend on
table statistics this server has no visibility into and could not honestly
be described as deterministic.

## Decision drivers
- §7.3's determinism gate rules out anything resembling a cost estimate —
  only structurally-countable properties (join count, nesting depth, join
  shape) qualify.
- §7.5 names cross joins specifically, not just "too many joins" — a
  categorical, unconditional rejection independent of any configured
  threshold, distinct from the configurable join-count/nesting-depth caps.
- ADR 0032 already established the exact config/resolution pattern this
  ADR reuses (`*LimitsProperties` pair + `Resolved*.of(profile, base,
  advanced)`), so a new parallel pattern was not invented — consistency
  with the existing per-profile limits mattered more than a "better" design.
- A cross join, over-cap join count, or over-cap nesting hidden inside a
  `WHERE EXISTS`/`IN`/scalar subquery is exactly as much a bypass as one
  in the top-level `FROM` clause — a validator that only walked the FROM
  clause would be measurably weaker than `SchemaAllowlistValidator`
  (via `TablesNamesFinder`) sitting right next to it, which already
  reaches subqueries everywhere in the tree. Confirmed during design by
  reading JSqlParser 5.3's own `TablesNamesFinder`/`ExpressionVisitorAdapter`
  source: `ExpressionVisitorAdapter` already implements the WHERE/HAVING/
  EXISTS/IN/scalar-subquery recursion and delegates any `Select` it finds
  straight back to a composed `SelectVisitor`, so no hand-written
  `ExpressionVisitor` overrides were needed to get that coverage.

## Decision
New profile-varying config, mirroring ADR 0032's pattern exactly:
- `QueryComplexityLimitsProperties` (`policy/sql`), bound at
  `db2mcp.query-complexity`: `maxJoins` (default 5), `maxNestingDepth`
  (default 3).
- `AdvancedQueryComplexityLimitsProperties` (`policy/sql`), bound at
  `db2mcp.advanced-query-complexity`: `maxJoins` (15), `maxNestingDepth`
  (6). Defaults are illustrative and tunable, not load-tested — same
  honesty framing ADR 0032 already used for its own defaults.
- `ResolvedComplexityLimits` (`policy/sql`) — `of(CapabilityProfile, base,
  advanced)`, same shape as `ResolvedQueryLimits.of`.

New `SqlComplexityValidator` (`policy/sql`), a `@Component` with one public
method, `validate(String sql)`. It parses the SQL independently (consistent
with every other validator in the chain), then walks the *whole* statement
tree with a fresh, call-local visitor (never mutable state on the
`@Component` itself, which is a concurrent Spring singleton):
- Rejects immediately and unconditionally the instant any join is a cross
  join — either an explicit `CROSS JOIN`, or a comma-separated FROM item
  (`Join.isSimple()`, confirmed during implementation to be JSqlParser's
  representation of `"T1, T2"` — `isCross()` alone does not cover this
  shape). This is categorical policy per §7.5, not a configurable
  threshold, and applies identically under every profile including
  `ADVANCED_QUERY`.
- Counts joins across the whole tree and rejects the instant the running
  count exceeds the resolved `maxJoins`.
- Tracks subquery nesting depth, incremented only for genuinely nested
  subqueries (a parenthesized FROM-clause subquery, or a `LATERAL`
  subquery) — CTE bodies and `UNION`/`INTERSECT`/`EXCEPT` branches are
  siblings of the statement referencing them, not nesting, and do not
  increment it. Rejects if depth exceeds the resolved `maxNestingDepth`.

The visitor implements `SelectVisitor<Void>` + `FromItemVisitor<Void>` +
`SelectItemVisitor<Void>` directly (the same dispatch shape
`SchemaAllowlistValidator`'s use of `TablesNamesFinder` already
establishes as this codebase's precedent for whole-tree SQL walks), and
composes one `ExpressionVisitorAdapter<Void>`, wired via
`.setSelectVisitor(this)`, to route `WHERE`/`HAVING`/each join's
`ON`/each select item's expression through the same walker — closing
exactly the gap a FROM-only implementation would have left open.

Wired into `Db2QueryAdapter` as a new gate (span `sql.complexity`) in both
`executeQuery` and `explainQuery` (which already run the identical
capability/structural/schema sequence per ADR 0033), immediately after
`scope.authorize`. New `PolicyDecisionReason.COMPLEXITY_EXCEEDED`
(audit-internal, distinguishing this rejection from a structural or schema
one in the audit log). Client-facing `ErrorCategory` stays
`VALIDATION_FAILED` — the same granularity structural rejections already
report; the client-visible signal is "your query failed validation," the
finer distinction lives in the audit trail, not the wire response.

`getCapabilities`'s `EffectiveLimits` gained `maxJoins`/`maxNestingDepth`,
resolved the same way in `Db2CapabilityAdapter` — a client can see the
active thresholds before ever hitting a rejection, the same rationale
`EffectiveLimits`'s existing fields already document.

## Explicitly out of scope
- **A scalar UDF that itself issues an expensive query inside DB2.** This
  is structurally invisible to any client-side SQL-text parse — not a
  scoping choice made for convenience, but the same category of gap ADR
  0008 already accepts for views (this server cannot see through opaque
  server-side objects). Nothing short of DB2-side plan inspection could
  close this, and that would reintroduce exactly the non-determinism §7.3
  rules out.
- Cost- or cardinality-based estimation. Ruled out directly by §7.3's
  determinism gate (see Decision drivers).
- Retroactively re-scoring `explainQuery`'s captured plan against these
  limits — `explainQuery` runs the same `sql.complexity` gate as
  `executeQuery` before DB2 is ever asked for a plan, so there is nothing
  further to check once a plan comes back.

## Consequences
- `getCapabilities` reports genuinely different `limits.maxJoins`/
  `limits.maxNestingDepth` under `ADVANCED_QUERY` vs.
  `SAFE_QUERY`/`METADATA_ONLY`, extending the same per-profile signal ADR
  0032 introduced for row/payload/cell limits.
- A deliberate, expected change to `mcp-output-contract-snapshot.json`
  (the only one of the two contract snapshots affected —
  `mcp-contract-snapshot.json` carries no limits data), not a regression.
- Two existing tests changed because the new gate's rejection is the
  correct, improved behavior, not a bug: `Db2QueryAdapterIntegrationTest`'s
  cross-join timeout test now uses a real `JOIN ... ON` with a
  deliberately non-selective `ON` clause (the comma-join it used before is
  now rejected before ever reaching DB2, so it could no longer exercise
  the JDBC timeout path); its span-tree assertion test gained
  `"sql.complexity"` in its expected span sequence.
- Cross-join rejection is unconditional under every profile, including
  `ADVANCED_QUERY` — "broader" never means a weaker guarantee (the same
  principle ADR 0032 already applied to its own limits).

## Verification / enforcement
`./mvnw verify -DskipITs` — unit tests (`SqlComplexityValidatorTest`:
accepted-within-limits cases; unconditional cross-join rejection via both
comma-join and explicit `CROSS JOIN`, under both `SAFE_QUERY` and
`ADVANCED_QUERY`; join-count and nesting-depth caps, including the
regression-proving case of a cross join and an over-cap join count hidden
inside a `WHERE EXISTS`/`IN` subquery, which a FROM-only implementation
would have wrongly accepted; CTE/`UNION` siblings not accumulating depth;
fail-closed on unparseable SQL), `ResolvedComplexityLimitsTest`
(parameterized over all three profiles), ArchUnit (no `..domain..`→
`..policy..` violation), JaCoCo coverage gate, MCP contract snapshots.
`scripts/smoke-test.sh` and `scripts/eof-shutdown-race-test.sh` re-run
against the packaged jar, confirming the new constructor parameters didn't
break bean wiring. Manual verification against the packaged jar (real
stdio MCP session, DB2 connection deliberately unreachable so a rejection
is distinguishable from a downstream DB2 failure by error code alone):
implicit and explicit cross joins rejected under both profiles; a query
within either profile's join/nesting cap passes the gate and reaches DB2
(observed as `DB2_EXECUTION_FAILED`/`08001`, not `VALIDATION_FAILED`); a
query over `SAFE_QUERY`'s join cap (6 joins) and over its nesting cap (4
levels) both rejected under `SAFE_QUERY` and both accepted under
`ADVANCED_QUERY` (cap 15/6); `getCapabilities` reports the correct
`maxJoins`/`maxNestingDepth` per profile.

## Related decisions
ADR 0006 (introduced `ADVANCED_QUERY`), ADR 0008 (structural/schema
validation this ADR's gate runs after; also the source of the "can't see
through views" precedent this ADR's UDF gap follows), ADR 0032 (the
config/resolution pattern this ADR mirrors for complexity limits), ADR
0033 (`explainQuery` reuses this same gate chain).
