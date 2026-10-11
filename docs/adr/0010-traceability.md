# ADR 0010: Provide end-to-end traceability without retaining sensitive data

## Status
Accepted — implemented and verified in this environment (`mvn test`
green, 84 tests; audit routing additionally verified against the real
packaged app, not just the test JVM — see Consequences).

## Date
Proposed 2026-08-12, Accepted 2026-08-12.

## Context
Nothing in this project currently records *why* a request was allowed
or rejected, beyond the exception thrown back to the immediate caller.
Master brief §8 wants every tool call reconstructable after the fact:
which tool, what policy decision, why, how long it took, how much data
moved — without ever retaining raw SQL, credentials, or row data in
that trail (§7.4). This matters specifically for `executeQuery`, which
has the richest decision surface (capability check → regex guard →
structural validation → schema allowlist → execution), all currently
invisible outside of exception messages nobody's logging yet.

## Decision drivers
- Must not put anything sensitive in the trail: no raw SQL text, no row
  data, no credentials. §7.4 explicitly wants a fingerprint or
  normalized form instead of raw SQL by default.
- Must not require a large refactor of the existing, tested guard chain
  — adding traceability should be additive, not a rewrite of
  `Db2QueryAdapter`'s validated exception-throwing behavior.
- Must distinguish *why* a rejection happened (which of the four guard
  stages failed) without over-committing to the full public-facing
  structured error model from brief §11.4 — that's explicitly Phase 5
  scope, not this one. The reason codes here are for the audit trail
  only, not (yet) part of any tool's return contract.
- Audit output must not corrupt the MCP stdio protocol stream (ADR
  0001) — stdout is off-limits for anything, including audit logs.

## Considered options
1. **A dedicated SLF4J logger (e.g. named `AUDIT`), configured via
   logback to a separate stream/file from the general application log,
   with each guard stage wrapped in try/catch-and-rethrow to record an
   event without changing any existing exception type or message.**
   Additive: every one of the ~80 existing tests keeps passing
   unchanged, since nothing about what's thrown or when changes — only
   a logging side effect is added around it.
2. **Refactor every validator to throw a common typed exception carrying
   an explicit reason code**, used both for audit and eventually the
   public error contract. Rejected for *this* ADR: touches every
   existing validator and the tests written against their current
   exception types (`IllegalArgumentException`/`IllegalStateException`)
   across Phase 3 — a much larger, riskier change than traceability
   alone justifies. Revisit this when Phase 5's structured error model
   is actually being built, where it belongs.
3. **Structured JSON logging from the start** (vs. plain key=value
   text). Deferred: brief §9.1 wants structured JSON "in an operational
   profile," which doesn't exist yet (no profiles are defined at all).
   Plain, greppable key=value text now; revisit format when Phase 4's
   observability half (Micrometer/OpenTelemetry) and any operational
   profile work happens.

## Decision
A dedicated `AUDIT` logger, routed to stderr (never stdout, per ADR
0001) via a distinct logback pattern, kept separate from the ordinary
application log stream. A correlation ID (UUID, generated fresh per
`executeQuery` call) is attached to every audit line for that call. Each
of the four guard stages in `Db2QueryAdapter.executeQuery` is wrapped in
its own try/catch-and-rethrow, so the audit log always knows exactly
which stage rejected a request — derived from *where* the catch block
is, not from parsing the exception's message — without changing any
exception's type, message, or the guard chain's existing behavior.

SQL is never logged raw. A `sqlFingerprint` is computed by
best-effort-parsing the input (never throws) into `STATEMENT_TYPE
[schema.table, schema.table, ...]` (e.g. `SELECT[ITSM.CUSTOMER,
ITSM.TICKET]`) — sorted, deduplicated table references, no literal
values, no column names. Unparseable input fingerprints as
`UNPARSEABLE`.

Reason codes for this pass are audit-internal only: `CAPABILITY_DISABLED`,
`INVALID_STATEMENT`, `SCHEMA_NOT_ALLOWED`, `EXECUTION_FAILED`,
`ALLOWED`. Not exposed through any tool's return value or thrown
exception — purely what gets written to the audit log.

## Consequences
- **Positive, and genuinely exercised:** every `executeQuery` call now
  produces a real audit line — correlation ID, decision, fingerprint,
  duration, row count, truncation, SQLSTATE on failure — verified by
  attaching a real logback `ListAppender` to the `AUDIT` logger and
  calling the adapter through success, schema-rejection, and
  execution-failure paths, not by inspecting the code and assuming it
  works. None of Phase 1-3's ~80 existing tests needed to change, as
  intended — this was purely additive.
- **Real finding, environment-specific:** `logback-spring.xml`'s custom
  `AUDIT` logger/file-appender routing only initializes when Spring
  Boot's `LoggingSystem` processes it — which plain JUnit tests with no
  `ApplicationContext` never trigger. During `mvn test`, logback falls
  back to its own default console configuration instead, so audit lines
  appeared on the console during test runs even though `additivity="false"`
  should have kept them out of the general stream. This looked like a
  bug at first. It wasn't: verified separately against the actual
  packaged jar (real `Db2McpServerApplication` startup, full Spring
  Boot logging initialization) that `logs/audit.log` is created, stdout
  stays empty, and zero `AUDIT`-tagged lines leak into stderr. The
  `ListAppender`-based unit tests still correctly verify event
  *content*, since they attach directly to the `AUDIT` logger
  regardless of which appender config is active — they just don't prove
  the file-routing configuration itself, which needed this separate,
  real-app check to confirm.
- **Fingerprint fidelity, confirmed adequate for the cases tested:**
  `SqlFingerprint` produced correct, useful output for every case
  exercised — `SELECT[ITSM.TICKET]`, multi-table joins sorted and
  deduplicated (`SELECT[ITSM.CUSTOMER,ITSM.TICKET]`), and `UNPARSEABLE`
  for genuinely broken input — no surprises requiring a fix.
- **Scope limits, as planned:** only `executeQuery` got this; the eight
  metadata/lookup tools still have no audit trail. Reason codes remain
  audit-internal only, not exposed through any tool's contract — the
  overlap with Phase 5's structured error model is accepted, deferred
  rework, not avoided.

## Verification / enforcement
`mvn test` (84 tests total) includes four new `Db2QueryAdapterIntegrationTest`
cases using a real logback `ListAppender`: audit event on success (with
correct fingerprint/row count/truncation), on schema-allowlist rejection,
on a real H2 execution failure (confirming the raw error/column name
does not leak into the audit log either, not just the thrown exception),
and confirming correlation IDs are unique per call. Separately, the
packaged jar was started for real to confirm `logs/audit.log` is created
and no audit content leaks into stderr — the file-routing configuration
itself, which the unit tests alone couldn't prove.

## Related decisions
ADR 0001 (stdout is protocol-only, audit output must respect that), ADR
0007 (the guard chain this instruments), ADR 0009 (H2-based testing —
audit log content will be verified the same way, not against live DB2).
