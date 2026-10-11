# ADR 0021: Adopt the database-portability master brief extension

## Status
Accepted (scope adopted; implementation phased — Phase P1 done via ADR
0020, Phase P2 partially done via ADR 0022 (still unverified against
live DB2), Phase P3 done via ADR 0027, Phase P4 done via ADR 0028, Phase
P5 done via ADR 0029 — all five phases now implemented; Phase P2's
live-DB2 verification is the one item this extension still leaves open).

## Date
Proposed 2026-08-14, Accepted 2026-08-14.

## Context
An independent code review of this repository (`docs/security/
code-review-2026-08-14.md`) shipped alongside a companion document,
`docs/master-brief-extension-database-portability.md`, proposing to
extend the master brief with explicit database-portability requirements:
move database-neutral result models out of the MCP transport package,
introduce a capability manifest and dialect descriptor, invert the
outbound-adapter dependency so a future Oracle/PostgreSQL adapter could
be added without rewriting MCP governance, and prove that inversion with
a fake test adapter rather than a real second implementation.

The document's stated motivation was a planned future consumer: **an AI
incident-analysis client**, intended to depend on this MCP server's
capabilities rather than its Db2-specific implementation details. That
consumer appears nowhere else in this project's history — not the original
master brief, not any prior ADR, not the implementation plan — so it was
not treated as established context on the document's word alone.
Confirmed directly with the project owner before any of this was acted
on: the planned client is real, and adopting the portability extension was
an explicit choice, not an inference from the uploaded file.

The document also contains a section titled "Claude implementation
instructions," addressed directly to the AI agent implementing this
project. That framing was deliberately not treated as authorization by
itself — external content directing this agent's own behavior is exactly
the kind of thing this project's own AI-development policy
(`docs/ai-development/development-policy.md`) says should be confirmed
with a human before broad scope is taken on, not executed because a file
said so.

## Decision drivers
- Real future context (the planned client) changes what "done" means for
  this project's contracts — worth capturing as a first-class decision,
  not silently absorbed.
- The extension explicitly does **not** ask for Oracle/PostgreSQL
  production adapters now — only architectural seams (ports, a
  capability manifest, a fake test adapter) so a second adapter could be
  added later without a rewrite. That scope is bounded and consistent
  with this project's existing incremental, ADR-at-decision-time
  history, not a re-scoping into a generic multi-database platform.
  Db2 stays the only implemented, tested database.
- The extension's own required working method (§15) explicitly orders
  closing P0 security findings before portability refactoring — matches
  this project's existing practice of fixing found bugs before building
  on top of the code that had them.
- A five-phase architectural refactor (dependency inversion, capability
  discovery, adapter selection, portability evidence) is large enough
  that attempting it in one pass would violate this project's own
  "small, independently tested commits" discipline the extension itself
  asks for.

## Considered options
1. **Adopt the extension as written, phase the implementation.** Chosen.
   The scope itself (Db2-first, adapter-ready, no premature second
   adapter) is compatible with everything this project already claims
   about itself; only the pacing needed a decision.
2. **Decline the extension, fix only the two P0 security findings.**
   Rejected: the project owner confirmed the planned client is a real
   planned consumer, so the portability requirement is real product
   context, not a hypothetical to defer indefinitely.
3. **Adopt the extension and implement all five phases immediately.**
   Rejected: contradicts the extension's own required working method
   (close P0 findings first, refactor in small independently-tested
   commits) and this project's established practice of writing an ADR
   at the point a phase is actually done, not speculatively for work not
   yet verified.

## Decision
Adopt `docs/master-brief-extension-database-portability.md` as an
addendum to `docs/master-brief.md`, governing this project's contracts
going forward the same way the original brief does. Implementation
proceeds in the phases the extension itself defines:

- **Phase P1 — security boundary correction: done.** ADR 0020
  (`lookupRefData` reclassified and governed; common-enough error
  sanitization added to every metadata tool). Payload/value-normalization
  limits (the other half of P1) remain open, tracked in
  `docs/implementation-plan.md`.
- **Phase P2 — Db2 correctness: partially done, ADR 0022.**
  `SPECIFICNAME`-based routine identity and `REFKEYNAME`-based
  foreign-key resolution are implemented, but unverified against live
  DB2. The live-Db2 integration-test profile itself (this phase's other
  deliverable) is not started.
- **Phase P3 — dependency inversion: done, ADR 0027.** Neutral result
  models moved from `mcp.contract` to `domain.model`; outbound ports
  renamed `MetadataPort`/`QueryPort` and relocated to
  `application.port.out`; Db2 SQLSTATE classification moved behind a new
  `DatabaseErrorClassifier` port, implemented in `adapter.db2`; two new
  ArchUnit rules, both proven able to fail before being trusted.
- **Phase P4 — capability discovery and adapter selection: done, ADR
  0028.** A versioned `getCapabilities` tool, always visible under every
  capability profile, reports effective capabilities/limits/database
  identity from a live JDBC version query. `db2mcp.database.product`
  makes Db2 adapter activation explicit; its single-value closed enum is
  the fail-fast mechanism for an unsupported selection, verified live
  against the real packaged jar. Also fixed a real, previously-shipped
  `NullPointerException` bug in `AuditEvent.allowed` found while
  extending its instrumentation pattern.
- **Phase P5 — portability evidence: done, ADR 0029.** A test-only
  `FakeDatabaseAdapter` (no `CapabilityPort` - see ADR 0029 for why),
  wired into the real `mcp.tool` classes and exercised end to end
  (`FakeAdapterMcpToolsTest`), plus genuinely reusable parameterized
  contract tests running identical assertions against both the real
  H2-backed Db2 adapters and the fake for `QueryPort` and `MetadataPort.
  lookupRefData` (the two Tier 1 surfaces where a fair comparison is
  possible). Zero `src/main` changes - proof that Phases P3/P4's
  dependency inversion was already correct.

Each phase gets its own ADR when implemented and verified, following
this project's existing pattern (Proposed before implementation,
Accepted once verified) — not written speculatively ahead of the work.

## Consequences
- **`docs/master-brief.md` now has a second governing document.**
  Anything in `docs/master-brief-extension-database-portability.md`
  conflicts-resolves in its own favor for portability-specific
  questions; the original brief still governs everything else.
- **Db2 remains the only implemented, tested database.** No Oracle or
  PostgreSQL dependency, adapter, or claim is added by this ADR —
  explicitly out of scope per the extension's own §3.3, and this ADR
  does not change that.
- **The public-repository Definition of Done (brief §19) is not yet
  re-evaluated against this extension.** `docs/implementation-plan.md`
  will need a new gate reflecting the extension's own "Gate B — ready
  for curated public repository" criteria once Phases P2–P5 land, not
  assumed complete by this ADR alone.
- **Every future phase's contract changes must go through a deliberate,
  reviewed diff** (extension §13's versioning rules) — Phase P2's
  `SPECIFICNAME` routine-identity change in particular will alter
  `describeProcedure`/`getProcedureSource`'s existing input shape, which
  the MCP contract snapshot (ADR 0013) will correctly flag as a change
  requiring explicit review, not something that can land silently.

## Verification / enforcement
This ADR records a scope decision, not a code change — no test suite
result attaches to it directly. Phase P1's actual verification is in ADR
0020. Each subsequent phase's ADR carries its own verification section.

## Related decisions
ADR 0020 (Phase P1), ADR 0022 (Phase P2), ADR 0027 (Phase P3), ADR 0028
(Phase P4), ADR 0029 (Phase P5 — all five phases of this extension are
now closed out), ADR 0009 (Tier 1/Tier 2 live-Db2 verification honesty —
Phase P2's live-Db2 integration-test requirement inherits this framework
directly, and it's the one item this extension still leaves open), ADR
0005 (layer separation — Phase P3 extends, not replaces, its existing
ArchUnit rules), ADR 0013 (MCP contract snapshot — the mechanism that
catches unintentional breaking changes across every phase).
