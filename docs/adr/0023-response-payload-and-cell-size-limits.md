# ADR 0023: Bound response payload and cell size, normalize JDBC values

## Status
Accepted — implemented and verified in this environment (`mvn verify`
green, 153 tests, up from 140; real H2 `Clob`/`Blob`/`ARRAY` values
exercised, not mocks; packaged jar's effective-config report confirms
the new limits are wired end to end through the real Spring context).
Fully Tier 1 (ADR 0009) — unlike ADR 0022's two fixes, everything here
lives entirely in `executeQuery`'s H2-tested path, so it needed no live
DB2 instance to verify for real.

## Date
Proposed 2026-08-14, Accepted 2026-08-15.

## Context
CR-005 from the 2026-08-14 external code review: `executeQuery`'s
`maxRows` bounds row *count*, but nothing bounded the *size* of an
individual cell or the total response. A single row containing a large
CLOB, BLOB, XML value, or binary value could still exhaust heap or MCP
payload size, or blow out an LLM client's context window, even under
the existing row cap. `ResultSet.getObject()` was also copied directly
into `QueryResult`'s rows with no normalization — an unrecognized
JDBC/driver-specific type's raw `toString()` could disclose whatever
internal detail that type happened to include, the same category of
risk ADR 0018/0020's error-sanitization work exists to prevent, just on
the success path instead of the error path.

Next in the review's own recommended remediation order, explicitly
flagged as something to close "before exposing the server to realistic
LLM-driven queries" — ahead of any portability refactoring.

## Decision drivers
- Must bound both a single cell and the whole response, independently -
  a payload cap alone wouldn't stop one pathological cell, and a
  cell cap alone wouldn't stop many moderately-large cells adding up.
- Must not materialize what it's about to discard. A `Blob`/CLOB whose
  content isn't going to survive to the client shouldn't be fully read
  into memory first just to be truncated afterward - defeats the point.
- Must not trust an unrecognized type's `toString()` by default -
  matches this project's existing sanitized-error discipline (ADR 0018,
  ADR 0020) applied to the success path.
- Truncation must never split a multi-byte UTF-8 character and produce
  invalid output - a naive byte-array substring is not safe here.
- Every claim needed to be genuinely testable in this environment, not
  asserted from documentation like ADR 0022's Tier 2 fixes had to be -
  this is the one CR-00x fix from the review with no live-DB2 blocker.

## Considered options
1. **A dedicated `Db2ValueNormalizer`, called per cell inside the
   existing row-building loop, plus a running payload-byte counter that
   stops adding rows once exhausted.** Chosen. Minimal surface change to
   `Db2QueryAdapter`'s existing structure; keeps `QueryResult`'s shape
   completely unchanged (truncation for either reason - row cap or
   payload cap - reuses the existing single `truncated: boolean` field),
   so no MCP contract snapshot change was needed for this ADR at all.
2. **Structured truncation metadata** (a `cellTruncated` flag per cell,
   a `payloadTruncated` field distinct from `rowCapTruncated`). Rejected
   for this pass: a real contract change (breaking, like ADR 0022's),
   and the in-band truncation marker embedded in the value string itself
   already tells the caller/LLM a cell was cut, which is the substantive
   goal. Noted as a deliberate simplification, not silently dropped.
3. **Base64-encode binary values up to the cell cap, instead of omitting
   them entirely.** Rejected: CR-005's own remediation explicitly allows
   either "bounded binary representation or explicit binary omission."
   Omission is simpler, avoids the encode/decode-budget interaction
   entirely, and an LLM diagnostic client has essentially no legitimate
   use for raw binary content anyway - the only thing that matters is
   that a `BLOB` column's presence and size are visible, not opaque.

## Decision
Added `Db2ValueNormalizer` (package-private, `adapter.db2`): normalizes
`null`/`Boolean`/`Number` unchanged; bounds `String` to `maxCellBytes`
via a UTF-8-boundary-safe truncation (walks back from the byte cap past
any UTF-8 continuation byte before decoding, so truncation never
produces invalid output); reads `Clob` via a bounded `getSubString`
call rather than materializing the whole value; omits `Blob`/`byte[]`
content entirely, replaced with `"<binary value omitted, N bytes>"`;
reads `SQLXML` via `getString()` then applies the same string bound;
treats `java.sql.Date`/`Time`/`Timestamp` and any `java.time.
temporal.TemporalAccessor` as safe-to-stringify (their `toString()` is
JDK-controlled, not driver-controlled); everything else becomes
`"<unsupported value type: ClassName>"` rather than a trusted raw
`toString()`. `QueryLimitsProperties` gained `maxCellBytes` (default 16
KiB) and `maxPayloadBytes` (default 1 MiB) - the same defaults the
adopted portability extension's example capability manifest already
showed (ADR 0021), not chosen independently. `Db2QueryAdapter`'s
row-building loop now normalizes each cell, accumulates an
approximate-byte running total, and stops adding rows once the budget
is exceeded - but always lets at least one row through regardless of
size, so a legitimately large single record doesn't come back as an
empty result. `EffectiveConfigReporter` and `application.yml` extended
to surface the two new limits.

## Consequences
- **Verified against real H2 driver types, not assumed from the JDBC
  spec.** Probed empirically before writing any code: `CAST(... AS
  CLOB)` returns `org.h2.jdbc.JdbcClob` (implements `Clob`); `CAST(...
  AS BLOB)` returns `org.h2.jdbc.JdbcBlob` (implements `Blob`); a
  `TIMESTAMP` column returns `java.time.OffsetDateTime`, not
  `java.sql.Timestamp` - all three now exercised by real integration
  tests, not mocked.
- **New tests, all against real H2 execution:**
  `executeQuery_boundsAnOversizedClobCell` (real `Clob`, truncation
  marker present, bounded read - not full materialization then cut);
  `executeQuery_omitsBlobContentEntirely` (real `Blob`, zero raw bytes
  in the result); `executeQuery_truncatesWhenPayloadBudgetExceededBeforeRowCap`
  (payload cap trips with `maxRows` still generous - proves it's a
  genuinely independent limit); `executeQuery_alwaysReturnsAtLeastOneRowEvenOverBudget`
  (a budget smaller than even one row - exactly one row still comes
  back, not zero); `executeQuery_unsupportedDriverTypeBecomesASafeMarker`
  (H2 `ARRAY`, which has no explicit case in the normalizer - the
  fallback path is real, not theoretical). `Db2ValueNormalizerTest`
  covers the UTF-8 boundary math directly, including a case built
  specifically to land the truncation cap mid-character and confirm the
  result re-encodes cleanly with no `U+FFFD` replacement character.
- **The CLOB byte cap is an approximation, stated plainly.**
  `Clob.getSubString` bounds by character count, not UTF-8 byte count -
  a multi-byte-heavy CLOB's truncated result can exceed `maxCellBytes`
  bytes after encoding. Good enough to stop an unbounded read; not a
  byte-exact guarantee for non-ASCII CLOB content. Plain `String`
  cells (which are already fully materialized by the driver regardless
  of size) get the exact, byte-precise treatment instead.
- **`QueryResult`'s shape is completely unchanged.** No MCP contract
  snapshot update was needed for this ADR - truncation for either
  reason (row cap or payload cap) reuses the existing `truncated`
  field, and cell-level truncation is signaled in-band via the value
  string itself, not a new structured field. A deliberate simplification
  (see Considered Options), not an oversight.
- **Full suite: 153 tests** (up from 140 after ADR 0022) - 13 new: 5
  integration (`Db2QueryAdapterIntegrationTest`), 8 unit
  (`Db2ValueNormalizerTest`) - all green under `mvn verify -DskipITs`,
  including the JaCoCo policy-coverage gate and ArchUnit layering rules.
  Packaged jar re-verified: `EffectiveConfigReporter`'s startup log now
  shows `maxCellBytes=16384 maxPayloadBytes=1048576` for real.
- **Not addressed by this ADR**: cell-level and payload-level truncation
  are not exposed as structured metadata (Considered Options #2);
  Db2-driver-specific object types (e.g. DECFLOAT wrappers, DB2's own
  XML handling) are Tier 2 - normalization for those is asserted from
  the standard JDBC API's documented contract, not verified against a
  real DB2 driver instance, the same honesty framing ADR 0009 already
  established for everything catalog-related.

## Verification / enforcement
`mvn verify -DskipITs` (153 tests, JaCoCo gate, ArchUnit). Packaged jar
run with default configuration; `EffectiveConfigReporter`'s startup log
confirms `maxCellBytes`/`maxPayloadBytes` resolve correctly through the
real Spring context.

## Related decisions
ADR 0007 (original `maxRows`/`timeoutSeconds` resource limits - this ADR
completes the payload-size gap that ADR 0007 and the adversarial
catalogue both explicitly left open), ADR 0009 (Tier 1/Tier 2 honesty -
this ADR is fully Tier 1, unlike ADR 0022), ADR 0018/0020 (sanitized
error handling - the same "don't trust an unrecognized type's raw
output" principle applied here to the success path), ADR 0021 (the
adopted portability extension - `maxCellBytes`/`maxPayloadBytes`'
defaults match its example capability manifest).
