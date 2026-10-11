# ADR 0038: Adopt a human-governed, cross-model AI engineering workflow

## Status
Accepted — this has been this project's actual practice for the review
that produced `docs/reviews/2026-09-01-master-brief-gap-review.md` and its
response matrix; this ADR formalizes it rather than introducing a new
process.

## Date
Proposed 2026-09-01, Accepted 2026-09-01.

## Context
ADR 0017 formalized this project's human-governed AI development workflow
(issue → AI proposal → human review → AI implementation → automated gates
→ human review → ADR/contract update), run by a single implementation
agent (Claude Code) and a single human owner. That workflow says nothing
about how a second, independent AI reviewer fits in — and this project now
routinely uses one: ChatGPT develops target architecture, master-brief
direction, and security/portfolio strategy, and separately reviews the
resulting repository against the real, current code. The first
full cycle of that practice produced
`docs/reviews/2026-09-01-master-brief-gap-review.md` (13 findings against
`docs/master-brief.md`) and its disposition in
`docs/reviews/2026-09-01-master-brief-gap-review-response.md`.

Running two different models in different roles is not, by itself, a
substitute for independent human security review, and this ADR exists
partly to say that plainly rather than let the practice imply otherwise.
Two AI systems can share correlated blind spots — the same training-data
gaps, the same tendency to trust a well-written summary over the artifact
it summarizes, the same class of reasoning error — and neither one has, or
should be treated as having, final authority over what the project accepts
as true or as risk-acceptable. This project's own established discipline
(ADR 0009's Tier 1/Tier 2 verification honesty, ADR 0017/§12.3's "AI
output is not evidence of correctness") already anticipates exactly this
failure mode; this ADR extends that discipline to a second AI role instead
of treating the second model's review as automatically authoritative
because it came from a different system.

## Decision drivers
- The workflow already exists in practice (this ADR's own production is
  the first complete example); the gap was that it was nowhere written
  down as a decision, matching ADR 0017's own reason for existing.
- A second AI's review is genuinely useful — it reads the whole repository
  fresh, unanchored to the implementation agent's own framing of what it
  built — but is exactly as fallible as the first AI, and must be
  validated against the real repository before anything in it is acted on
  or recorded as fact.
- This project's honesty norm requires the workflow to state what it is
  not: not an independent human security audit, not a guarantee that
  correlated model errors are eliminated, not a process where either AI
  holds final decision authority.

## Decision

### Three responsibilities
- **Human project owner — Marco.** Owns requirements, priorities, and
  architectural decisions; decides which findings are accepted as risk
  versus required to be fixed; gives final approval on scope, direction,
  and what gets committed. Every workflow step below terminates in this
  responsibility, not in either AI's output.
- **ChatGPT.** Supports target-architecture direction, master-brief
  development, and security/portfolio strategy; performs a separate
  implementation/compliance review of the resulting repository against
  the master brief, architecture, and stated security requirements. Does
  not implement production code in this repository and does not have
  write access to it.
- **Claude Code.** Implements the approved direction as production code,
  tests, ADRs, and technical documentation; investigates review findings —
  from ChatGPT or any other source — against the actual codebase before
  any finding is recorded as confirmed, rejected, or accepted risk.

### The workflow
1. The human project owner defines a requirement or idea.
2. ChatGPT helps develop the target architecture, constraints, and a
   master-brief-level plan for it.
3. The human project owner approves or adjusts the resulting direction.
4. Claude Code implements the agreed scope, with automated verification
   (tests, ArchUnit, contract snapshots, JaCoCo gates, and — where a live
   external system is unavailable in this environment — an explicit,
   stated verification-tier distinction rather than a silent assumption).
5. ChatGPT reviews the resulting repository against the master brief,
   architecture, and stated security requirements — reading the actual
   repository, not a summary of it.
6. Claude Code validates every finding from that review against the real
   code, classifying each as `Confirmed`, `Rejected with evidence`,
   `Accepted risk`, or `Deferred` (this project's own addition to the
   three-way scheme a review document may itself suggest, used when a
   finding is real but explicitly out of scope for the current change) —
   each classification backed by a specific file, line, or test, not by
   restating the finding.
7. The human project owner decides acceptance: which findings must be
   fixed, which are accepted as risk, and what — if anything — changes as
   a result.

### What this workflow is not
- **Not an independent human security audit.** Both reviewing and
  implementing roles in steps 2, 4, 5, and 6 are AI systems; nothing in
  this workflow substitutes for a qualified human security reviewer if and
  when this project needs one (for example, before public promotion — see
  `docs/master-brief.md` §15.1 and ADR 0037's own promotion gate).
- **Not a guarantee against correlated model error.** Using two different
  model providers reduces, but does not eliminate, the risk that both
  share a blind spot, a bias toward a plausible-sounding but wrong
  conclusion, or a failure to notice the same category of gap. Findings
  from either AI are inputs to be checked, not verdicts.
- **Not a process where either AI has final decision authority.** Step 7
  is not a formality — the human project owner can reject a `Confirmed`
  finding's proposed remediation, decline to act on an `Accepted risk`
  classification, or override any AI's classification outright.
- **Not a substitute for reading the real code.** Step 6 explicitly
  requires inspecting actual source and tests. A review's classification
  based on the implementation agent's own summary of what it built, rather
  than the artifact itself, is not this workflow — it is the exact failure
  this workflow exists to prevent (see ADR 0017/§12.3's "AI output is not
  evidence of correctness," applied here to review claims as much as to
  implementation claims).
- **Not indifferent to verification tier.** Any implementation claim
  entering this workflow — from Claude Code's own implementation report or
  from a validated review finding — must distinguish unit/H2 verification,
  live-Db2 (or other live external system) verification, and unverified
  assumption, per ADR 0009's Tier 1/Tier 2 split. A finding validated only
  against unit/H2-tested code is not evidence about live-Db2 behavior.

### Traceability
Every review this workflow produces is kept as two separate documents: the
review itself, preserved as received (not silently rewritten to look
resolved), and a separate response document recording each finding's
classification and supporting evidence. Both stay in the repository after
the findings they describe are acted on, so the disposition of any
historical finding remains checkable against real commits rather than
relying on memory or a changelog summary. See
`docs/reviews/2026-09-01-master-brief-gap-review.md` and
`docs/reviews/2026-09-01-master-brief-gap-review-response.md` for the
first full example.

## Consequences
- **The workflow that already produced this ADR's first example is now a
  recorded decision, not an informal habit.** Future reviews from ChatGPT
  or any other external/second-model source follow the same seven steps
  and the same four-way classification, rather than each being handled
  ad hoc.
- **A review document's findings are never treated as fixed just because a
  review says so, and never treated as resolved just because
  implementation followed a review.** The two-document pattern (review +
  response) makes the gap between "reported" and "validated" and between
  "validated" and "fixed" visible in the repository itself.
- **This ADR does not change who approves what.** It documents a second AI
  role feeding into the same human-governed approval step ADR 0017 already
  established; it does not delegate any decision away from the human
  project owner.
- **The workflow adds real cost (a validation pass over every finding
  before acting on it) in exchange for catching cases where a review is
  wrong** — the same trade this project already makes for AI-generated
  implementation claims under ADR 0009/ADR 0017, now applied symmetrically
  to AI-generated review claims.

## Verification / enforcement
Not independently testable the way a code-level ADR's decision is — this
is a documentation and process ADR, like ADR 0017. Its claims are
checkable directly against this repository: does
`docs/reviews/2026-09-01-master-brief-gap-review.md` preserve the review
as received, does
`docs/reviews/2026-09-01-master-brief-gap-review-response.md` cite a
specific file/line or test for every classification, and does each
`Confirmed` finding's disposition trace to a real, separate change rather
than being silently folded into the same commit as this governance
documentation.

## Related decisions
ADR 0017 (the single-agent human-governed workflow this ADR extends to a
second AI role, not replaces), ADR 0009 (the Tier 1/Tier 2 verification
honesty this ADR's verification-tier requirement is built on).
