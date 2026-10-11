# ADR 0009: Verification strategy in the absence of a live DB2 instance

## Status
Accepted — implemented and verified in this environment (`mvn test`
green, 65 tests total, 5 of them real H2 integration tests; packaged
jar confirmed not to bundle the test-scope H2 dependency).

## Date
Proposed 2026-08-11, Accepted 2026-08-12.

## Context
This development environment has no Docker daemon and no network path
to any raw-TCP database — confirmed directly (`/root/.ccr/README.md`
explicitly lists "raw-TCP databases" and "non-443 ports" as unsupported
through this session's egress proxy, not just untested). No DB2
instance is reachable from here, and none is expected to be for the
foreseeable future (the maintainer is currently away from any computer).
Everything built so far (Phases 1-3) has been verified by compiling,
unit-testing with mocks, and smoke-testing the packaged jar's startup
behavior — never by executing against a real database.

This matters unevenly across the tool set. Two categories exist:

- **Seven tools query DB2's system catalog** (`listSchemas`,
  `listTables`, `describeTable`, `getTableDependencies`,
  `listProcedures`, `describeProcedure`, `getProcedureSource`) — SQL
  against `SYSCAT.*` views that are specific to DB2 itself. No other
  database product implements these views; there is no substitute that
  can verify this SQL actually runs correctly.
- **Two tools run generic SQL against application tables**
  (`executeQuery`, `lookupRefData`) — SQL that doesn't depend on DB2's
  catalog, only on whatever schema/tables happen to exist. This *can* be
  approximated by another JDBC-compatible database.

Continuing to build (Phase 4 onward) without ever exercising a real
JDBC round-trip means every layer — the validator chain, `JdbcTemplate`
row mapping, `ResultSetMetaData` handling, row-cap truncation — has only
ever been tested in isolation, never end-to-end. That's a real gap
worth closing for the part of the system where it's possible to close.

## Decision drivers
- Must not create false confidence: a green test suite must not imply
  DB2-specific SQL has been verified when it hasn't. This project has
  already had to walk back overclaims twice (the original baseline
  status document, the DBeaver-driver ADR) — the same discipline applies
  here.
- Should raise real confidence wherever genuinely possible, rather than
  waiting indefinitely for DB2 access before writing any integration
  test at all.
- The substitute database, if used, must not silently diverge from
  DB2 in ways that matter to this project's specific design (schema-
  qualified table references, per ADR 0008).

## Considered options
1. **H2, in `MODE=DB2` compatibility mode**, with an application-table
   schema modeled on the ITSM demo schema, for real (non-mocked)
   integration tests of `executeQuery`/`lookupRefData` only. H2 has
   proper multi-schema support (`CREATE SCHEMA`), matching this
   project's schema-qualified design, and a DB2 compatibility mode
   built for exactly this kind of portability testing.
2. **SQLite**, similarly embeddable. Rejected: no native multi-schema
   support (`ATTACH DATABASE` is a workaround, not equivalent), and
   dynamic/loose column typing that would diverge further from DB2's
   `ResultSetMetaData` behavior than H2's does — both weaknesses land
   directly on things this project's design depends on.
3. **No substitute database — mocks only, wait for live DB2.** Rejected
   as the sole strategy: leaves the entire JDBC integration seam
   (`JdbcTemplate.query`, real `ResultSetMetaData`, real row streaming
   and truncation) completely unexercised indefinitely, with no defined
   timeline for when that changes.
4. **Skip testing entirely for the seven catalog tools until DB2 access
   exists.** Not really an alternative — this is simply the accurate
   description of what's already true and unavoidable for that half of
   the tool set. No in-memory database can substitute for `SYSCAT.*`.

## Decision
A two-tier verification strategy, tracked explicitly rather than left
implicit:

- **Tier 1 — real integration coverage, achievable now:** add H2
  (`MODE=DB2`) as a test-scope dependency. Recreate the ITSM demo
  schema's application tables (not DB2's catalog) in H2. Write
  integration tests for `executeQuery` and `lookupRefData` that run
  through the real `Db2QueryAdapter`/`Db2MetadataAdapter` code path
  against this H2 instance — not mocks. This verifies the full guard
  chain (regex → `SqlStructuralValidator` → `SchemaAllowlistValidator`
  → JDBC execution → row mapping → truncation) actually works end to
  end for the part of the system that doesn't depend on DB2-specific
  catalog views.
- **Tier 2 — remains unverifiable here, explicitly flagged, not
  silently accepted:** the seven catalog-view tools stay at their
  current verification level (compiles, unit-tested row-mapping against
  mocked `ResultSet`s, SQL cross-checked against IBM's published
  `SYSCAT` documentation) until a real DB2 instance is reachable —
  whether that's the maintainer's own machine or a future VM. No
  in-memory substitute changes this. `getTableDependencies`' join
  remains the single highest-risk untested query in the project.
- Every test class and every relevant section of
  `docs/implementation-plan.md` states which tier it belongs to, so
  "passes" never gets read as "verified against DB2" for something that
  wasn't.

## Consequences
- **Positive, and genuinely exercised, not just anticipated:** the row
  cap and truncation flag (ADR 0007) were verified against a real
  `ResultSet` returning exactly 1001 rows from H2's `SYSTEM_RANGE` table
  function, not asserted against mocked behavior — `rowCount()` was 1000
  and `truncated()` was `true`, confirming `JdbcTemplate.setMaxRows`
  actually works as assumed. The schema allowlist was verified to block
  a table that genuinely exists and is genuinely queryable
  (`OTHER_SCHEMA.SECRET`), not merely a fictitious name — closing a gap
  the earlier mock-only tests couldn't close (a mocked `JdbcTemplate`
  never proves the guard runs *before* a real query would have
  succeeded).
- **No surprises requiring a fix this time** — unlike ADR 0007's
  null-parse discovery. All 5 integration tests (join query, row-cap
  truncation, allowlist rejection of a real table, filtered and
  unfiltered `lookupRefData`) passed on the first run against H2 in
  `MODE=DB2`.
- **Risk of overclaiming, guarded against explicitly:** H2's DB2 mode is
  an approximation, not a DB2 emulator. A query that behaves correctly
  against H2 is not proof it behaves identically against real DB2 —
  every Tier 1 test and this ADR say "H2-verified," never "DB2-verified."
- **New test-only dependency and schema to maintain**
  (`src/test/resources/itsm-h2-schema.sql`, a second, smaller copy of
  the ITSM table definitions alongside the original DDL from the
  creation prompt) — confirmed not to leak into the runtime jar (H2 is
  test-scoped; verified by inspecting the packaged jar's contents).
- **Does not reduce the Tier 2 gap at all**, as intended. The seven
  catalog-view tools remain unverified end-to-end; this ADR was never
  meant to close that gap, only to be honest about which half of the
  gap actually can be closed without DB2.

## Verification / enforcement
`mvn test` (65 tests total) includes:
`Db2QueryAdapterIntegrationTest` (join query with real column/row
assertions, 1001-row truncation, real-table allowlist rejection) and
`Db2MetadataAdapterLookupRefDataIntegrationTest` (filtered and
unfiltered `lookupRefData`), both against
`ItsmH2Database` (H2, `MODE=DB2`, seeded from `itsm-h2-schema.sql`).
Packaged jar inspected directly (`unzip -l`) to confirm no H2 classes
ship in the runtime artifact.

## Related decisions
ADR 0007 and ADR 0008 (the validator chain this would exercise
end-to-end), `docs/implementation-plan.md` (tracks the still-open
DB2-only verification gap for the seven catalog tools).
