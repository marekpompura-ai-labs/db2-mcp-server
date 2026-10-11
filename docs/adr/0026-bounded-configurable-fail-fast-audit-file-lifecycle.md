# ADR 0026: Bounded, configurable, fail-fast audit-file lifecycle

## Status
Accepted — implemented and verified in this environment (`mvn verify`
green, 162 tests, up from 159; the fail-fast behavior confirmed against
the real packaged jar, not just a unit test).

## Date
Proposed 2026-08-16, Accepted 2026-08-16.

## Context
CR-011 from the 2026-08-14 external code review: the audit logger wrote
to `logs/audit.log` via a plain, non-rolling `FileAppender` - no size or
time rotation, no retention bound, no configurable path, and no defined
behavior if the destination couldn't be opened. Four required fixes,
quoted directly from the finding: "use a rolling policy with bounded
retention, make the path configurable, document permissions and
ownership, and define behavior if the audit destination cannot be
opened."

## Decision drivers
- The audit trail is a security control (ADR 0010) - silently losing it
  because a disk filled up or a path was wrong is worse than a loud
  startup failure, the same reasoning ADR 0015 already applied to
  `db2mcp.query.*`.
- A diagnostic/operational feature must still not become a *new* way for
  the server to misbehave: unlike ADR 0015's `EffectiveConfigReporter`
  (which must never crash startup, since it only reports), an unwritable
  audit destination genuinely should crash startup, since serving tool
  calls with a broken audit trail defeats a stated security control.
- Rotation limits themselves (file size, history, total cap) don't need
  to be independently operator-configurable to satisfy the finding -
  only the *path* is named explicitly ("make the path configurable").
  Fixed, documented rotation defaults are simpler and still bounded;
  matches CR-005/ADR 0023's same choice not to make every limit a new
  property.

## Considered options
1. **Switch `AUDIT_FILE` to a `RollingFileAppender` with
   `SizeAndTimeBasedRollingPolicy`, add `db2mcp.audit.file` as a fourth
   `@ConfigurationProperties` record wired into `logback-spring.xml` via
   `<springProperty>`, and add a small `ApplicationRunner` that
   independently probes the same resolved path is writable, throwing a
   dedicated exception if not.** Chosen.
2. **Rely solely on Logback's own behavior when it can't open a file.**
   Rejected outright, then partially reconsidered after testing (see
   Consequences) - Logback's default behavior is to disable the
   appender silently and continue, which is exactly the "audit trail
   disappears with no visible error" failure mode CR-011 flags. Not
   sufficient on its own even though it turned out to matter more than
   expected once actually tested.
3. **Hand-written rotation (manual file-size check plus rename) instead
   of Logback's built-in rolling policy.** Rejected - reinvents a
   well-tested mechanism the project already depends on, same reasoning
   ADR 0015 used to reject hand-written config validation in favor of
   Bean Validation.

## Decision
`logback-spring.xml`'s `AUDIT_FILE` appender is now a
`RollingFileAppender` with a `SizeAndTimeBasedRollingPolicy`: rolls
daily or at 10MB (whichever comes first), keeps 30 days of history,
capped at 300MB total on disk. The file path is read from
`db2mcp.audit.file` via `<springProperty>`, backed by a new
`AuditProperties` record (`@ConfigurationProperties(prefix =
"db2mcp.audit")`, `@NotBlank`, default `logs/audit.log`) -
`EffectiveConfigReporter` (ADR 0015) now reports the resolved
`auditFile` value alongside the three other `db2mcp.*` properties
records, none of which can hold a credential.

A new `AuditFileAvailabilityValidator` (`ApplicationRunner`) resolves
the same path, creates missing parent directories, and opens the file
in append mode as a real writability probe - closing it immediately
without writing. On `IOException` it throws
`AuditFileUnavailableException` with an ADR-0015-style structured
message (`Property: db2mcp.audit.file / Value: "..." / Resolved: "..."
/ Reason: ...`), which propagates out of `SpringApplication.run()`
uncaught and exits the JVM with a non-zero code - the server never
reaches a state where it's serving tool calls with a broken audit
trail.

Permissions and ownership (the finding's third requirement) are
documented here rather than enforced in code, since filesystem
ownership isn't something the JVM process can mandate on its own
deployment environment: the directory `db2mcp.audit.file` resolves into
should be owned by the service account the server runs as, mode `0750`
(owner read/write/execute, group read/execute, no world access); the
log files themselves should end up `0640` (owner read/write, group
read, no world access) - Logback creates files with the process's
default umask, so an operator should set a restrictive umask on the
service account rather than relying on Logback to chmod anything after
the fact. Audit lines never contain raw SQL or row data (ADR 0010), but
they do contain schema/table fingerprints and SQLSTATEs, which is
enough to warrant not making the file world-readable.

## Consequences
- **A genuinely surprising, verified finding about which mechanism
  actually fires first.** Pointing the packaged jar's
  `DB2MCP_AUDIT_FILE` at a path whose parent component is an existing
  regular file (a deterministic, permission-independent way to force a
  real open failure) does produce a hard startup failure with exit code
  1 - but the actual stack trace is `IllegalStateException: Logback
  configuration error detected`, thrown by Spring Boot's own
  `LogbackLoggingSystem.reportConfigurationErrorsIfNecessary`, called
  from `LoggingApplicationListener.onApplicationEnvironmentPreparedEvent`
  during environment preparation - well before `SpringApplication.run()`
  refreshes the context far enough to construct
  `AuditFileAvailabilityValidator` at all. Spring Boot aborts on its own
  once Logback's internal `StatusManager` records a file-open `ERROR`
  status, independent of anything this project added.
  `AuditFileAvailabilityValidator` never got the chance to throw in this
  exact scenario - confirmed by grepping the real stderr output for
  `AuditFileUnavailableException` (absent) versus `Logback configuration
  error detected` (present five times - once per nested/rethrown
  exception in Spring Boot's own `SpringApplication.run()` failure-
  handling chain).
- **Kept anyway, deliberately, not as dead code.** The validator is
  still real, still independently unit-tested
  (`AuditFileAvailabilityValidatorTest`, three tests including one that
  forces the exact same failure mode and gets the exception directly,
  bypassing Spring entirely), and still the only part of this design
  that produces the project's own structured, ADR-0015-style error
  message rather than a raw Logback stack trace. Spring Boot's
  Logback-error-threshold behavior is real and reproducible in this
  environment, but it is an internal framework mechanism, not a
  documented contract this project can rely on across Spring Boot
  versions - the same reasoning ADR 0002 already applies to keeping
  three independent read-only layers even though any one of them might
  be sufficient in a given scenario. Recorded here exactly as found,
  not smoothed over: this is "belt and suspenders" for real, verified
  by testing rather than assumed, and the belt turned out to already be
  fastened by the framework in the one scenario tested.
- **Positive path verified against the real packaged jar too**: default
  configuration (no `DB2MCP_AUDIT_FILE` override) creates `logs/audit.log`
  on startup with no crash, `EffectiveConfigReporter`'s line includes
  `auditFile=logs/audit.log`, and stdout stays 0 bytes throughout -
  nothing about this change touches the MCP protocol stream (ADR 0001).
- **Full suite: 162 tests** (up from 159) - 3 new
  (`AuditFileAvailabilityValidatorTest`), plus `EffectiveConfigReporterTest`
  updated for the new constructor parameter and a new `auditFile=...`
  assertion. All green under `mvn verify -DskipITs`, JaCoCo policy-package
  gate and ArchUnit layering rules unaffected (`AuditProperties` and
  `AuditFileAvailabilityValidator` live in `adapter.observability`,
  already a leaf package with no inbound layering restriction from
  `configuration`).

## Verification / enforcement
`mvn verify -DskipITs` (162 tests). Packaged jar re-verified twice: (1)
default config - `logs/audit.log` created, no crash, effective-config
line correct, stdout 0 bytes; (2) `DB2MCP_AUDIT_FILE` pointed at an
unreachable path - process exits non-zero, no MCP session ever starts,
confirmed via the real stack trace rather than assumed.

## Related decisions
ADR 0010 (traceability - the audit trail this ADR makes durable), ADR
0015 (config validation and effective-config report - the fail-fast
pattern and structured error-message style this ADR reuses, and the
report `AuditProperties` now feeds), ADR 0001 (stdout is protocol-only -
this change is entirely on the stderr/file side), ADR 0002 (the
"independent layers, none claimed sufficient alone" reasoning this
ADR's Consequences section explicitly extends to the Logback-vs-validator
finding).
