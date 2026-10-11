# ADR 0005: Separate transport, application, and Db2 adapter layers via ports, enforced by ArchUnit

## Status
Accepted

## Context
The master brief's dependency rule (`docs/master-brief.md` §4.2) requires
that the MCP tool layer never call `JdbcTemplate`, `DataSource`, or JDBC
directly — all database access must go through an application-layer port.
A rule stated only in documentation is easy to violate by accident once
more tools are added; it needed to be checked mechanically.

## Decision
For the first vertical slice (`listSchemas`), the codebase is split into:

- `mcp.contract` — typed records (`SchemaInfo`), no framework or JDBC
  dependency.
- `mcp.tool` — `@Tool`-annotated methods (`SchemaTools`), depends only on
  an application-layer interface.
- `application.metadata` — `MetadataService`, the port interface the tool
  layer depends on.
- `adapter.db2` — `Db2MetadataAdapter`, implements the port using
  `JdbcTemplate` against `SYSCAT.SCHEMATA`.

No `policy`, `domain`, or `operations` packages were created yet — those
show up when Phase 3 (security policy) and later phases actually need
them, not pre-emptively. Creating empty packages for brief-suggested
layers with no content is scaffolding without value.

The dependency rule is enforced by an ArchUnit test
(`LayeringArchitectureTest`), not just documented: `noClasses().that()
.resideInAPackage("..mcp.tool..").should().dependOnClassesThat()
.resideInAnyPackage("org.springframework.jdbc..", "javax.sql..",
"java.sql..")`. The rule was verified to actually fail by deliberately
introducing a `JdbcTemplate` field into `SchemaTools` and confirming the
test caught it, then reverting — a passing architecture test that has
never been seen to fail proves nothing.

## A real pitfall this surfaced
`@ArchTest` on a **static field** silently produces zero executed tests
under Maven Surefire 3.5.3+ (a confirmed upstream regression,
[ArchUnit#1442](https://github.com/TNG/ArchUnit/issues/1442)) — the build
reports `Tests run: 0, Failures: 0` with no error, which looks like
"nothing to check" rather than "the check never ran." `@ArchTest` on a
**method** (taking a `JavaClasses` parameter and calling `rule.check
(classes)`) is unaffected and is what this project uses. This is
recorded here because it's exactly the kind of false-positive-green
result the project's own quality bar exists to catch — an architecture
test that reports success without ever running is worse than no test.

## Consequences
- Adding a tenth tool that queries the database directly from `mcp.tool`
  fails the build automatically, not just code review.
- The port (`MetadataService`) currently has one trivial adapter
  implementation; if a second implementation is ever needed (e.g. a
  fake/in-memory adapter for faster unit tests), the interface boundary
  already exists to support it.
- Every future `@ArchTest` in this project must be method-based, not
  field-based, or it risks silently not running — this constraint is
  project-specific tooling debt, not an architectural choice, and should
  be revisited if ArchUnit or Surefire fixes the regression upstream.

## Update — Phase 5: full layer set (2026-08-13)
The original rule only covered `mcp.tool` vs. JDBC. By Phase 5 the
codebase has grown a real `application`, `policy`, and `domain` layer,
each of which could, in principle, quietly reach into `adapter` the same
way `mcp.tool` could once reach into JDBC — the original rule wouldn't
catch either. `LayeringArchitectureTest` was expanded from one `@ArchTest`
to five: `mcp.tool` must not touch JDBC directly (original rule), `mcp.tool`
must not depend on `adapter` at all, `application` must not depend on
`adapter` (the port/adapter inversion the whole design rests on), `policy`
must not depend on `adapter`, and `domain` — the innermost layer
(`AuditEvent`, `PolicyDecisionReason`) — must not depend on `mcp`,
`application`, `policy`, `adapter`, or `configuration`. Each was confirmed
true of the actual codebase via grep before being locked in as a rule.

**A second real pitfall, same "prove the test can fail" discipline as
above:** the `domain` rule was checked for genuine meaningfulness by
deliberately violating it — first by adding an *unused import* of
`AuditLogger` to `PolicyDecisionReason.java`. That did **not** trip the
rule: ArchUnit analyzes bytecode-level class dependencies, and an unused
import leaves no trace in compiled bytecode. Replacing it with an actual
field reference (`static final Class<AuditLogger> LEAK =
AuditLogger.class;`) did trip it, with a detailed violation report citing
the field's type and the static initializer. Reverted after confirming.
Worth recording separately from the Surefire pitfall above because it's a
different failure mode: that one was a tooling regression that silently
skips the check; this one is a modeling mistake — assuming source-level
change equals bytecode-level dependency — that anyone hand-verifying an
ArchUnit rule this way could repeat.

Full suite: 94 tests (up from 90), all green. Packaged-jar smoke test
re-run at both `METADATA_ONLY` (8 tools registered — `executeQuery`
correctly excluded) and `SAFE_QUERY` (9 tools registered), stdout 0 bytes
and zero `lsof` network file descriptors in both.
