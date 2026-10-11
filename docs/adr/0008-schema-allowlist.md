# ADR 0008: Enforce a schema allowlist for executeQuery, deny by default

## Status
Accepted — implemented and verified in this environment (`mvn test`
green, 60 tests; packaged jar smoke-tested with
`DB2MCP_ALLOWED_SCHEMAS=ITSM`).

## Date
Proposed 2026-08-11, Accepted 2026-08-11.

## Context
`executeQuery` (ADR 0006/0007) currently validates statement *type*
(must be a `Select`) but not statement *scope* — any table the DB2
connection's grants permit can be queried. The master brief calls for a
schema/table allowlist (§6.2, §7.3) as a further, independent
restriction: even a syntactically valid, read-only `SELECT` shouldn't be
able to reach arbitrary schemas just because the DB2 user happens to
have grants there (e.g. a shared/broader read grant used across
multiple tools, not scoped tightly per application).

This isn't numbering trivia: master brief §13.1 groups "allowlists"
under its own ADR-0007, but that topic (already Accepted as ADR 0007 in
this repo) explicitly deferred this as separate, undesigned work with
real alternatives — hence a distinct ADR here rather than reopening
0007. (This repo's ADR numbers no longer track the brief's numbering at
all — see `docs/adr/README.md`.)

## Decision drivers
- Must not silently allow everything when unconfigured — matches this
  project's established fail-closed default (ADR 0006's `METADATA_ONLY`
  default, ADR 0007's parse-failure-rejects behavior).
- Must not require per-table configuration for a single-schema demo
  setup (the ITSM schema) to be usable without excessive friction.
- Must decide what happens to a table reference DB2 could resolve via
  `CURRENT SCHEMA` (i.e. written without an explicit schema prefix) that
  this validator cannot verify statically.

## Considered options
1. **Schema-level allowlist, deny by default.** Configure a list of
   allowed schema names; any qualified `schema.table` reference outside
   that list is rejected; any *unqualified* table reference is also
   rejected, since its resolved schema depends on session state
   (`CURRENT SCHEMA`) this validator doesn't control and can't verify.
2. **Table-level allowlist** (explicit `schema.table` pairs). Finer
   granularity, but heavier to configure for no real benefit at this
   project's scale (a personal tool / single demo schema) — schema-level
   is the same granularity every other tool already uses
   (`listTables(schema)`, `describeTable(schema, table)`, etc.).
3. **No application-level allowlist — rely solely on DB2 grants.**
   Rejected for consistency with this project's established
   defense-in-depth pattern (ADR 0002's three independent read-only
   layers): the DB2 grant is one layer, not a substitute for an
   application-level check.
4. **Allow by default when unconfigured**, requiring an explicit opt-out
   to restrict. Rejected: inconsistent with every other safe-default
   decision in this project so far (`METADATA_ONLY`, parse-failure
   rejection) — an empty allowlist should mean "nothing is allowed yet,"
   not "everything is allowed."

## Decision
Schema-level allowlist (option 1). Configured via
`db2mcp.allowed-schemas` (empty list by default → every `executeQuery`
call is rejected under `SAFE_QUERY` until schemas are explicitly
configured). Table references are extracted from the already-parsed
statement via JSqlParser's `TablesNamesFinder`; any reference without an
explicit schema qualifier is rejected outright, not resolved against a
guessed default schema.

## Consequences
- **Positive:** a second, independent restriction on top of statement-
  type validation (ADR 0007) — narrows what a syntactically valid
  `SELECT` can actually reach, without depending on DB2 grants alone.
- **Friction, accepted deliberately:** `SAFE_QUERY` is unusable until
  `db2mcp.allowed-schemas` is set, and every query must schema-qualify
  its table references (`SELECT * FROM ITSM.TICKET`, not
  `SELECT * FROM TICKET`) even when DB2 itself would resolve the
  unqualified form correctly via `CURRENT SCHEMA`. This is a usability
  cost taken on purpose, matching the project's fail-closed pattern.
- **No surprises this time, worth noting explicitly:** unlike ADR 0007's
  `CCJSqlParserUtil.parse("")` null-return discovery, `TablesNamesFinder`
  behaved exactly as expected on the first implementation pass —
  quoted identifiers (`"ITSM"."TICKET"`), mixed-case schema names, and
  multi-table `FROM` lists were all handled correctly by the tests
  written for them, no follow-up fix needed.
- **Table name extraction only sees the parsed statement's own text.**
  It cannot see through views, so a `SELECT` against an allowed-schema
  view that itself reads from a disallowed schema would pass this
  check — that's a real, currently-unaddressed gap, not something this
  ADR claims to solve.

## Verification / enforcement
`mvn test` (60 tests total) includes `SchemaAllowlistValidatorTest`:
accepts qualified references (including quoted and case-varied) to the
allowed schema; rejects references to a schema outside the allowlist,
unqualified references, mixed allowed/disallowed multi-table queries,
and everything when the allowlist is empty. Not yet covered: the
view-indirection gap noted above, and this hasn't been exercised
against a live DB2 instance.

## Related decisions
ADR 0002 (defense-in-depth precedent), ADR 0006 (capability profiles),
ADR 0007 (statement-type validation this builds on top of).
