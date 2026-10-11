# ADR 0019: Health and readiness for a stdio-only server

## Status
Accepted — implemented and verified in this environment (`mvn verify`
green, 133 tests; a real bug found and fixed by testing against the
packaged jar with multiple real datasource scenarios, not assumed
correct from the unit tests alone).

## Date
Proposed 2026-08-13, Accepted 2026-08-13.

## Context
Master brief §9.4 specifies concrete rules for health/readiness that
this project had not yet addressed with a decision either way:

- Application liveness must not require a live database query.
- Readiness may validate configuration and a bounded database
  connection check.
- Health output must not expose credentials or internal topology.
- Stdio mode needs an appropriate alternative to HTTP-only operational
  endpoints, such as logs/metrics export or an optional management
  port.

ADR 0015 already covers the configuration half of readiness (startup
validation, fails fast with a specific property/value/reason). The
database-connection half was an open gap - `docs/implementation-plan.md`
flagged it directly: "a decision (or ADR note) on the health/readiness-
signal question for a stdio-only server," left unresolved through
Phase 5 and Phase 6.

## Decision drivers
- No HTTP endpoint exists to expose `/health`/`/ready` on (ADR 0001,
  ADR 0011) - deliberate, not an oversight, so this ADR must not
  quietly reverse that by adding a management port.
- The connectivity check itself must never become the reason the
  application fails to start - the same principle ADR 0015 already
  established for `EffectiveConfigReporter`, and one this ADR's own
  implementation initially violated before being caught by testing.
- Output must follow the same credential/topology sanitization already
  established (ADR 0007, ADR 0015, ADR 0018) - SQLSTATE only, never a
  raw driver message.

## Considered options
1. **A bounded database connectivity check, logged once at startup via
   `ApplicationReadyEvent`, never gating startup.** Chosen - matches
   brief §9.4's explicit stdio-mode allowance ("logs/metrics export...
   alternative to HTTP-only operational endpoints") without adding any
   new network surface.
2. **An optional management port exposing a real `/health` endpoint.**
   Rejected: brief §9.4 offers this as one option among several
   ("such as... or an optional management port"), but it directly
   conflicts with this project's stronger, already-established
   commitment to zero network exposure (ADR 0001, re-verified three
   separate times since - ADR 0011, ADR 0012, ADR 0016). Not worth
   reopening that commitment for a feature the brief itself treats as
   optional.
3. **No connectivity check at all - configuration validation (ADR 0015)
   is "readiness enough."** Rejected: brief §9.4 explicitly separates
   "validate configuration" from "a bounded database connection check"
   as two different things readiness may cover; configuration being
   valid doesn't mean Db2 is actually reachable.

## Decision
Add `Db2ConnectivityChecker`, an `@EventListener(ApplicationReadyEvent
.class)` component that performs one bounded connection check
(`DataSource.getConnection()` + `Connection.isValid(5)`, bounded by the
existing `spring.datasource.hikari.connection-timeout: 10000` - reused,
not a new property) and logs the sanitized result. Never throws,
never gates startup - liveness (the process being up, accepting MCP
messages) does not depend on this succeeding, per brief §9.4's explicit
rule.

## Consequences
- **A real bug found by testing against the packaged jar with a
  deliberately mismatched datasource, not assumed away:** the first
  version only caught `SQLException`. Testing revealed that a Hikari
  connection-pool *configuration* problem (a driver/URL mismatch) throws
  a plain `RuntimeException` before ever reaching JDBC's checked
  exception hierarchy - that exception escaped the
  `ApplicationReadyEvent` listener uncaught and crashed
  `SpringApplication.run()` entirely, directly contradicting this
  class's own stated "never a startup gate" design goal. Fixed by also
  catching `RuntimeException`, logging a sanitized warning instead
  (message deliberately omitted for an unclassified `RuntimeException`,
  since its message isn't guaranteed sanitized the way a JDBC
  `SQLState` is; full detail logged at `DEBUG` only). Re-verified
  against the packaged jar: no crash, `EffectiveConfigReporter`'s line
  now correctly follows it.
- **Verified across three real scenarios, not just one:**
  - Genuine success: unit test against a real H2 connection (`OK`
    logged). Not re-verified against the packaged jar specifically,
    because H2 is deliberately test-scope-only and absent from the
    packaged runtime artifact (ADR 0009) - confirmed still true here,
    incidentally, when a packaged-jar H2 override failed with `Cannot
    load driver class: org.h2.Driver`, exactly as expected.
  - Configuration-error failure (the bug above): packaged jar, no
    crash, sanitized log line.
  - **Genuine network failure, the realistic production case**: real
    DB2 driver configured, a genuinely unreachable hostname
    (`dummyhost.invalid`). Logged `Db2 connectivity check: FAILED
    (SQLSTATE=08001)` - `08001` is the real, standard SQLSTATE class for
    a connection exception, correctly surfaced. `scott`/`tiger` (the
    test credentials used) confirmed absent from the entire log output.
    The check resolved in under 2 seconds here (DNS failure is fast),
    not the full 10-second bound - stated as observed, not claimed as a
    guaranteed timing.
- **No new network surface added** - `lsof` was not re-run for this ADR
  specifically since nothing about listening sockets changed; this
  check only opens an *outbound* JDBC connection, the same kind
  `Db2QueryAdapter` already opens for every real query.
- **Full suite: 133 tests** (up from 132 before the bug fix's test was
  added), all green under `mvn verify -DskipITs`.

## Verification / enforcement
`Db2ConnectivityCheckerTest` (5 cases: real H2 success, sanitized
SQLSTATE failure, failure with no SQLSTATE available, connection
reported invalid, and the `RuntimeException`-doesn't-propagate
regression test) plus the three packaged-jar scenarios described above.

## Related decisions
ADR 0001 (no network exposure - why no management port), ADR 0009
(H2 absent from the packaged jar - independently reconfirmed here),
ADR 0015 (configuration validation - the other half of readiness;
same "never a startup gate" principle this ADR's own bug violated and
then fixed), ADR 0018 (the same SQLSTATE-only sanitization discipline).
