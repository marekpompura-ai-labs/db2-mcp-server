# ADR 0017: Adopt AI-driven, human-governed development

## Status
Accepted — this has been this project's actual practice since ADR 0007;
this ADR formalizes it and points at the detailed policy document, rather
than introducing a new process.

## Date
Proposed 2026-08-13, Accepted 2026-08-13.

## Context
Master brief §12 requires a documented AI-development governance policy:
a required workflow (issue → AI proposal → human review → AI
implementation → automated gates → human review → ADR/contract update →
reviewed merge) and AI-specific rules (AI output isn't evidence of
correctness, no inventing Db2 behavior, no silently weakening a policy to
pass a test, dependency additions need real review). This has, in fact,
already been how every ADR from 0007 onward was produced - written
`Proposed` before implementation, `Accepted` only once code and `mvn
verify` backed it up, Consequences updated with real findings rather than
anticipated ones. What was missing was the policy document itself and an
ADR recording the decision to run development this way, at the point
where formalizing it stopped being premature and started being overdue -
this project's own stated preference for capturing a decision at the
point it's made (see `docs/adr/README.md`'s "Status lifecycle" section).

## Decision drivers
- The workflow already exists in practice; the gap was that it was
  nowhere written down as a decision, only inferable from reading ADR
  history.
- Master brief §12.4 asks for one real, sanitized example change
  lifecycle in the repository - not a curated illustration invented for
  the occasion, but an actual commit.
- This project's honesty norm (established across ADR 0009's Tier
  1/Tier 2 split, ADR 0015's and ADR 0016's real-bug admissions) applies
  here too: the brief's step 8 ("merge through a reviewed pull request")
  is not how this repository currently operates, and the policy document
  needed to say so rather than imply a formal review process that
  doesn't exist yet for a single-maintainer private lab.

## Decision
Add `docs/ai-development/development-policy.md`, covering the brief's
required workflow, the AI-specific rules with a concrete example from
this repository for each one, and one real change lifecycle (ADR 0016 -
picked because it demonstrates the workflow catching a genuine bug, not
because it's a clean success story). The document explicitly states
where current practice differs from the brief's team-oriented framing
(no second human reviewer yet, so steps 3/6 happen in direct
conversation rather than a formal PR review) instead of glossing over
it.

## Consequences
- **Every AI-specific rule in §12.3 is backed by a real example from
  this repository, not a restatement of the rule.** "AI must not invent
  supported Db2 behavior" points at ADR 0009's documented history of
  catching exactly that failure mode from an earlier, unrelated AI
  session (the false DBeaver driver-sourcing claim, corrected in ADR
  0003). "Generated tests must include assertions that would fail for an
  unsafe implementation" points at the adversarial catalogue's two
  documented real gaps found by tests written against real policy code.
- **The formal-PR-review gap is stated, not hidden.** Anyone reading the
  policy document knows exactly which part of brief §12.2's workflow
  this repository doesn't yet follow and why (no second reviewer exists
  for a private, single-maintainer lab), rather than discovering the gap
  by noticing no PRs exist in the repository's history.
- **No new process was introduced.** This ADR and the policy document
  describe what ADR 0007 through ADR 0016 already did; nothing about how
  future work gets done changes as a result of writing this down.

## Verification / enforcement
Not independently testable the way a code-level ADR's decision is - this
is a documentation and process ADR. Its claims are checkable directly
against this repository's own git and ADR history, which is the point:
every assertion in `docs/ai-development/development-policy.md` cites a
specific, real ADR or commit rather than describing the policy in the
abstract.

## Related decisions
Every prior ADR is evidence for this one. Most directly: ADR 0009 (the
Tier 1/Tier 2 honesty split this policy's tone follows), ADR 0015 and ADR
0016 (real bugs found and recorded rather than quietly fixed - the
concrete example this ADR's Repository Evidence section is built from).
