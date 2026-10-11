# ADR 0045: A hard application-result payload bound and the BoundedList envelope (#62)

## Status
Accepted

## Date
Proposed 2026-09-10, Accepted 2026-09-10.

## Context
RR-P0-1 of the 2026-09-09 ChatGPT re-review (issue #62) found that
`maxPayloadBytes` was not what the master brief asks for. ADR 0039 had
introduced `JsonPayloadSizer` to replace an even weaker per-cell content
model, and that was a real improvement — but it measured the wrong thing,
and its own javadoc said so:

- it explicitly excluded the enclosing response envelope — column
  descriptors, wrapper keys, the correlation ID, the commas between
  accumulated rows, the outer array/object syntax;
- `executeQuery` summed individually serialized rows, so a response could
  exceed the configured limit while every individual row was inside it;
- `explainQuery` measured only `result.operators()`, excluding
  `statementText` — the caller's own echoed SQL, which can dwarf the
  operator list;
- the six generic metadata tools measured their result *after* execution
  for the metric and never enforced the limit at all;
- `lookupRefData` calculated truncation, audited it, and then returned a
  bare `List<ReferenceDataEntry>` — the caller could not tell a complete
  result from a cut one;
- `JsonPayloadSizer.byteLength` returned `0` when serialization failed,
  so an unmeasurable value counted as free and passed the budget check.

The through-line is that a size bound is only meaningful if it governs
what is actually sent, and only honest if the party on the other side is
told when it bit. Neither held.

> **Note on the filename.** This file keeps its original slug
> (`0045-hard-universal-payload-bound.md`) because it is a stable
> identifier referenced from the ADR index, CHANGELOG and other ADRs.
> The title above reflects the corrected scope; the slug does not.

## Scope of the guarantee - what this bound does and does not cover

Stated precisely, because an earlier revision of this ADR called the bound
"universal" and said it measured "what actually crosses the boundary".
Both were too strong, and the 2026-09-17 re-review (R2-P1-1) was right to
say so.

**What is bounded:** the fully assembled *application result object* of
every data-bearing tool - `executeQuery`, `explainQuery`, the list-producing
metadata tools, `lookupRefData`, `describeProcedure` and
`getProcedureSource`. That object is serialized and measured as it will be
rendered, envelope and correlation ID included, and the measured object is
the returned object.

**What is not bounded:**

- **`getCapabilities`.** `Db2CapabilityAdapter` never calls `PayloadBudget`.
  Its response is a small fixed-shape manifest rather than a result set, so
  it carries no unbounded-growth risk - but it is outside the invariant, and
  saying otherwise would be false. Tracked as #94.
- **MCP transport framing.** What is measured is the application object, not
  the emitted JSON-RPC line: `result.content` wrapping, text escaping applied
  by the protocol layer, the JSON-RPC envelope, request id and protocol
  metadata all sit outside the measurement. The tests serialize the Java
  object with their own `ObjectMapper`; none of them measures the bytes Spring
  AI actually writes to the transport.

So `maxPayloadBytes` is an **application result budget**, not a wire-level
cap. For its purpose - preventing an unbounded result set from being
assembled and returned - that is the right boundary, since transport overhead
is small and does not scale with result size. A genuine wire-level guarantee
would require enforcement at the MCP callback boundary and an end-to-end test
measuring the emitted line; that is a separate decision, not something this
ADR should be read as having made.

## Decision

**1. Measure the complete serialized response, not its row content.**
`PayloadBudget` replaces `JsonPayloadSizer`. It assembles a candidate
response — envelope, correlation ID and all — serializes it, and admits
the largest prefix of elements whose fully assembled response fits.
Nothing is estimated and no envelope overhead is reserved by guesswork:
the object that was measured is the object that is returned, and
`Fitted.byteLength()` is that measurement, which is also what
`recordResultBytes` now reports. The search is a binary search, so an
already row-capped list costs O(log n) serializations.

**2. Every list-producing tool returns `BoundedList<T>`.**
`{items, rowCount, truncated, correlationId}`. This is a **breaking
change to the MCP contract** of `listSchemas`, `listTables`,
`describeTable`, `getTableDependencies`, `listProcedures` and
`lookupRefData`, and `mcp-output-contract-snapshot.json` moves
accordingly. It was chosen over the alternative of leaving those six
returning bare lists and refusing over-budget results outright, because
an AI client that receives 500 of 900 tables and cannot tell will reason
confidently from a partial catalog — the failure mode is silent and
downstream, which is worse than a smaller result that says so.

`rowCount` is the number of items actually present, never a
pre-truncation total: reporting the latter would mean reading past the
server's own cap to learn a number, which defeats the cap.

**3. Fail closed, in both directions.**
- A response whose envelope alone exceeds the budget (very wide column
  metadata against a small `maxPayloadBytes`, say) raises
  `RESULT_LIMIT_EXCEEDED` rather than being sent anyway. This is the case
  the previous model handled by returning zero rows with `truncated:
  true` — a response *over* the cap, presented as enforcement of it.
- A serialization failure raises rather than returning zero.
- `describeProcedure` and `getProcedureSource` have no list to trim, so
  an over-budget result is refused. These two are the likeliest to carry
  a large blob of procedure DDL, which is exactly the case a "hard" bound
  has to cover to deserve the name.

This gives `ErrorCategory.RESULT_LIMIT_EXCEEDED` its first real caller;
its enum javadoc previously noted it was unreachable. The audit trail
distinguishes it with a new `PolicyDecisionReason.PAYLOAD_LIMIT_EXCEEDED`,
so "refused, too large" is not conflated with a genuine execution
failure — nor with a truncated success, which still audits as `ALLOWED`
with `truncated: true`.

**4. The metadata row cap can now detect itself.**
The metadata `JdbcTemplate` reads `maxRows + 1` (the trick `lookupRefData`
already used) so "there were more" is distinguishable from "there were
exactly this many". Under the previous exactly-`maxRows` cap those six
methods could not detect their own truncation at all — a residual gap
this repo's own source comment named. The envelope's `truncated` flag is
now honest for row-cap truncation as well as payload truncation.

## Consequences

- Clients must be updated: six tools' responses change shape. This is a
  private, pre-release repository (brief §15.1), so there is no external
  compatibility obligation, and the alternative was leaving a documented
  bound that did not hold.
- `explainQuery` can now refuse a request whose echoed `statementText`
  alone exceeds the budget. That is a behaviour change for very large
  submitted SQL, and the honest one — the previous version simply did not
  count those bytes.
- Adversarial coverage asserts the property directly, on the real
  serialized response rather than on internal accounting: wide column
  metadata, JSON escaping, multibyte UTF-8, a zero-row result, and an
  oversized first item (the "we must return at least one row" exemption
  that ADR 0039 had already rejected, now re-proven against the complete
  envelope). `PayloadBudgetTest` additionally pins that the search finds
  the *largest* fitting prefix, not merely some fitting prefix.
- Supersedes the payload-accounting half of ADR 0039 and the
  `recordResultBytes` sourcing described in ADR 0041; both remain
  accurate records of what was decided at the time.
