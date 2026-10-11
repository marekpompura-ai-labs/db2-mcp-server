# ADR 0022: SPECIFICNAME-based procedure identity and REFKEYNAME-based foreign key resolution

## Status
Accepted — implemented, verified in this environment (`mvn verify`
green, 140 tests; the deliberate MCP contract snapshot change reviewed
and committed), **and now verified against a real DB2 instance**
(2026-08-17, DB2 Community Edition 12.1.5.0, local Docker container -
`docs/demo/live-db2-test-prerequisites.md`). Both fixes are promoted from
Tier 2 to **Tier 1** (ADR 0009): `docs/demo/guided-scenarios.md`'s
live-DB2 verification checklist ran CR-003's overloaded-procedure case
and CR-004's unique-key-referencing-FK case against real `SYSCAT` catalog
views, not just documented semantics, and both matched exactly. Two real
bugs were found and fixed on the way to a clean run, both in
`docs/demo/`'s demo assets (not this ADR's application code) - see
Consequences.

## Date
Proposed 2026-08-14, Accepted 2026-08-14.

## Context
The same external code review that found CR-001/CR-002 (ADR 0020) also
reported two Db2 correctness findings, next in the review's own
recommended remediation order (item 3, before payload limits or any
portability work):

1. **CR-003 — stored-procedure overloads are not uniquely identified.**
   `listProcedures`/`describeProcedure`/`getProcedureSource` selected
   procedures using only `PROCSCHEMA`/`PROCNAME`. DB2 allows multiple
   specific routines to share a schema and name with different parameter
   signatures (`SYSCAT.PROCEDURES`/`PROCPARMS` expose `SPECIFICNAME` for
   exactly this). `describeProcedure` would silently return whichever
   overload the query happened to return first;
   `getProcedureSource` the same; an MCP client had no way to select the
   procedure it actually meant.
2. **CR-004 — foreign-key resolution assumed the parent key is always a
   primary key.** `getTableDependencies`' `TABLE_DEPENDENCIES_SQL` joined
   to the referenced key via `SYSCAT.TABCONST.TYPE = 'P'`. A foreign key
   can reference a unique constraint instead of the table's primary key;
   `SYSCAT.REFERENCES.REFKEYNAME` already names the exact constraint DB2
   resolved the FK against, so filtering to `TYPE = 'P'` could silently
   return no relationship, or resolve the wrong one, for any FK targeting
   a unique key.

Both are Tier 2 by ADR 0009's own framework — this environment has no
live DB2 instance to execute the corrected SQL against, and H2 (used for
`executeQuery`/`lookupRefData`'s Tier 1 coverage) has no `SYSCAT` views
at all, so there is no way to write an integration test proving either
fix correct here. This is the same limitation every other
`SYSCAT`-backed method in `Db2MetadataAdapter` already carries, stated
plainly rather than glossed over.

## Decision drivers
- Fix must match documented DB2 LUW catalog semantics, not a guess —
  same discipline the file's own header comment already commits to
  ("verified against IBM's published catalog view reference before
  writing this SQL, not from memory").
- CR-003 is a genuine, deliberate MCP contract change (input parameter
  renamed from `procedureName` to `specificName` on two tools, output
  shape of `ProcedureInfo`/`ProcedureSource` gains a field) — the
  contract snapshot test (ADR 0013) exists specifically to force this
  kind of change through a reviewed diff, not let it happen silently.
- CR-004 is SQL-only — no contract shape change, since the same columns
  are still selected and aliased identically.
- Don't invent verification that doesn't exist. Both fixes are stated
  as unverified against live DB2 rather than quietly upgraded to
  "tested" — consistent with every Tier 2 claim elsewhere in this
  project.

## Considered options
1. **CR-003: rename the disambiguating parameter to `specificName`,
   propagate through the port, adapter, and MCP tool layer; regenerate
   the golden contract snapshot deliberately.** Chosen — matches the
   review's own required remediation ("add specificName to procedure
   discovery and subsequent tool inputs... define the contract migration
   deliberately").
2. **CR-003 alternative: keep `procedureName` as the parameter, add an
   optional `specificName` for disambiguation only when needed.**
   Rejected: leaves the default (ambiguous) path exploitable by an LLM
   client that doesn't know to ask for `specificName`, and this project
   is still pre-1.0 private-lab software with no external consumers to
   preserve compatibility for — a clean break is cheaper than a
   half-migrated parameter.
3. **CR-004: join through `r.REFKEYNAME` directly, dropping the
   `SYSCAT.TABCONST` join and `TYPE = 'P'` filter entirely.** Chosen —
   simpler than the original query (one fewer join) and correctly
   handles both primary-key and unique-key targets, since `REFKEYNAME`
   already names the specific constraint regardless of its type.

## Decision
`ProcedureInfo` and `ProcedureSource` gained a `specificName` field.
`LIST_PROCEDURES_SQL` now selects `SPECIFICNAME` and orders by
`PROCNAME, SPECIFICNAME` for deterministic overload ordering.
`PROCEDURE_INFO_SQL`/`PROCEDURE_PARAMS_SQL`/`PROCEDURE_SOURCE_SQL` now
filter on `PROCSCHEMA = ? AND SPECIFICNAME = ?`, not `PROCNAME`.
`Db2MetadataAdapter.describeProcedure`/`getProcedureSource`,
`MetadataService`'s matching interface methods, and
`SchemaTools`'s `@Tool`/`@ToolParam` all renamed their second parameter
to `specificName`, with an explicit "pass specificName from
listProcedures, not procedureName" note in the client-visible tool
descriptions.

`TABLE_DEPENDENCIES_SQL`'s two halves (`OUTBOUND`/`INBOUND`) now join
`SYSCAT.KEYCOLUSE pk` directly via `pk.CONSTNAME = r.REFKEYNAME AND
pk.TABSCHEMA = r.REFTABSCHEMA AND pk.TABNAME = r.REFTABNAME AND
pk.COLSEQ = fk.COLSEQ`, removing the `SYSCAT.TABCONST` join and its
`TYPE = 'P'` filter.

## Consequences
- **The MCP contract snapshot changed exactly as expected, nothing
  more.** Deleted and regenerated deliberately (ADR 0013's own
  procedure for an intentional change); the diff touches only
  `describeProcedure`/`getProcedureSource`'s input schema (`procedureName`
  → `specificName`, both descriptions updated) and `listProcedures`'
  description — every other tool's contract is byte-for-byte unchanged.
- **No existing test broke or needed weakening.** Neither
  `describeProcedure`, `getProcedureSource`, nor `getTableDependencies`
  had any test coverage beyond the contract snapshot before this change
  (confirmed by grep across `src/test/`) — expected, since all three are
  Tier 2 (`SYSCAT`-backed, no H2 equivalent) and have been since ADR
  0009. This ADR doesn't add coverage that didn't exist; it corrects SQL
  that was untestable in this environment before and remains so now.
- **`docs/demo/guided-scenarios.md` scenario 3 updated** to show
  `specificName` (obtained from `listProcedures`) flowing into
  `describeProcedure`/`getProcedureSource`, not a literal
  `procedureName` string.
- **Both fixes are now verified against live DB2** (2026-08-17, real DB2
  12.1.5.0). `docs/demo/itsm-schema.sql` creates exactly the two cases
  this ADR's own suggested test named: two `STP_FIND_TICKETS` procedures
  sharing a schema/name with different signatures (CR-003), and
  `SERVICE_CATALOG_NOTE.SERVICE_NAME` referencing `SERVICE_CATALOG`'s
  unique constraint rather than its primary key (CR-004). Confirmed
  live: `listProcedures` returns both overloads distinguished only by
  `specificName`; `describeProcedure`/`getProcedureSource` for each
  `specificName` return that overload's own, different parameter list and
  source body, never the other's; `getTableDependencies(SERVICE_CATALOG)`
  reports the inbound `FK_NOTE_SERVICE_NAME` relationship with
  `pkColumn: "SERVICE_NAME"`, not `SERVICE_ID` - `REFKEYNAME` resolution
  confirmed correct, not just plausible. Full record in
  `docs/demo/guided-scenarios.md`'s checklist and `CHANGELOG.md`.
- **Two real bugs found and fixed while getting to a clean live run**,
  both in `docs/demo/`'s demo assets, not this ADR's application code:
  `itsm-schema.sql`/`itsm-test-data.sql` used `;` as the statement
  terminator for ordinary statements while only the two `CREATE
  PROCEDURE` bodies used `@`, so the documented `db2 -td@ -f ...`
  invocation (required by those bodies' internal `;`) failed immediately
  with `SQL0104N` - fixed by making every top-level terminator
  consistently `@`. Separately, `itsm-schema.sql`'s `GRANT USAGE ON
  SCHEMA ITSM TO ROLE ITSM_MCP_READONLY` failed with `SQL0557N` - `USAGE`
  isn't a valid DB2 LUW privilege on a `SCHEMA` object - removed, since
  the role's `SELECT`/`EXECUTE` grants don't require any schema-level
  privilege in DB2 LUW. Neither bug touched this ADR's `Db2MetadataAdapter`
  SQL, which was correct as designed.
- **Full suite: 140 tests**, unchanged in count from ADR 0020 (this ADR
  didn't add new automated tests, since none were possible without live
  DB2) — all green under `mvn verify -DskipITs`, including the JaCoCo
  policy-coverage gate and ArchUnit layering rules.

## Verification / enforcement
`mvn verify -DskipITs` (140 tests, unchanged pass count, deliberately
regenerated contract snapshot reviewed above). Live-DB2 verification
performed 2026-08-17 against real DB2 12.1.5.0 - see Consequences and
`docs/demo/guided-scenarios.md`'s live-DB2 verification checklist (every
item checked).

## Related decisions
ADR 0009 (Tier 1/Tier 2 verification honesty — the framework both fixes
in this ADR are explicitly filed under), ADR 0013 (MCP contract
snapshot — the exact mechanism that caught and forced review of CR-003's
breaking change), ADR 0020 (the companion P0 security fix from the same
external review), ADR 0021 (adoption of the database-portability
extension whose own Phase P2 this ADR implements).
