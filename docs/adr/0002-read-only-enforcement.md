# ADR 0002: Enforce read-only access at three independent layers

## Status
Accepted

## Context
One of the exposed MCP tools (`executeQuery`) lets the connected AI agent
run ad-hoc SQL against a production-adjacent DB2 database. The SQL text
originates from an LLM, which is a non-deterministic caller — the same
request can produce different SQL on different runs, and there is no human
review step between generation and execution. Allowing write access
(`INSERT`/`UPDATE`/`DELETE`/DDL) through that path would turn a schema
exploration tool into an uncontrolled data-mutation surface.

## Decision
Enforce read-only access at **three independent layers**, so that no single
misconfiguration or bug removes the guarantee:

1. **Database grants** — the DB2 user the server connects as holds
   read-only privileges at the database level. Even if every layer above
   this were bypassed, the database itself refuses writes.
2. **Connection pool** — HikariCP is configured with `read-only: true`,
   so the JDBC connection itself is marked read-only at the driver level.
3. **Application guard** — `executeQuery()` inspects the incoming SQL text
   and rejects anything that doesn't start with `SELECT` or `WITH` before
   it reaches the database at all. A prefix check alone is insufficient on
   its own: `SELECT 1; DROP TABLE ...` starts with `SELECT` and would pass
   a prefix-only check. This was caught by a unit test written specifically
   to probe the guard (`Db2QueryAdapterTest`) before any live DB2 was ever
   involved — the guard also rejects any SQL containing a semicolon, so no
   multi-statement text can reach the database regardless of prefix.

This is defense in depth deliberately, not redundancy for its own sake:
each layer protects against a different failure mode (a misconfigured DB
grant, a pooling library bug, an application-level parsing gap).

The same identifier-injection risk applies to the schema-discovery tools
(e.g. `lookupRefData`), which build SQL by concatenating schema/table/column
names that JDBC cannot parameterize as bind variables. Those identifiers are
validated against an allowlist regex (`^[A-Za-z0-9_#$@]+$`) and rejected on
mismatch *before* the SQL string is built — not sanitized after the fact.

## Consequences
- **No write capability, by design.** The server cannot be used for data
  fixes, migrations, or any mutation — that is out of scope, not an
  oversight. A future write-capable tool would need its own ADR covering
  confirmation flow, audit logging, and blast-radius limits.
- **Query results are still bounded by the DB user's read grants** — the
  app-level guard is a second gate, not a substitute for correct database
  permissions.
- **Every new `@Tool` method that builds SQL dynamically must be
  re-checked against this pattern** (allowlist identifiers before
  concatenation, bind values as `?` wherever possible) — this is called out
  explicitly in the project's security checklist so it isn't forgotten as
  new tools are added.
