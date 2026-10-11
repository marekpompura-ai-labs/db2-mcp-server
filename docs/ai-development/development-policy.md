# AI-driven development governance

## 12.1 Core principle

> AI-assisted, human-governed software development with automated
> verification and auditable architectural decisions.

Applied literally in this repository, not as an aspiration: every
Accepted ADR in `docs/adr/` exists because a decision was made, an AI
implemented it, and automated gates plus a human verified the result -
in that order, every time, with the reasoning trail kept rather than
summarized away afterward.

## 12.2 Required workflow

1. Start from an issue or written requirement with acceptance criteria.
2. Let the AI propose design and implementation options.
3. Human reviews assumptions, risk, API impact, and architecture.
4. AI may implement within the approved scope.
5. Automated tests and quality gates verify behavior.
6. Human reviews the diff, security impact, and generated tests.
7. Update ADR, contract snapshot, threat model, and documentation when
   relevant.
8. Merge through a reviewed pull request.

### How this project actually runs that workflow

Steps 1-7 are followed as written and are independently checkable in this
repository's own history: `docs/implementation-plan.md` is the running
requirement backlog (phase by phase, each with stated acceptance
criteria); ADRs are written `Proposed` - decision, drivers, and
alternatives considered, on the record before implementation - and only
marked `Accepted` once code, tests, and `mvn verify` back it up, with
Consequences updated to what was actually found (not just what was
anticipated). Every ADR from 0007 onward follows this pattern; see
`docs/adr/README.md`'s "Status lifecycle" section for how and when that
became the standing practice.

**Step 8 is honestly not yet how this repository operates, and this
document says so rather than implying otherwise.** This is currently a
single-maintainer private lab (`docs/master-brief.md` §15.1): there is no
second human reviewer to open a pull request toward, so "human review"
in steps 3 and 6 happens directly in conversation between the maintainer
and the AI - the maintainer sets direction and scope, asks clarifying
questions, and has repeatedly corrected the AI when it got something
wrong (see the "Repository evidence" section below for a real example).
Formal pull-request review is the intended process once work is promoted
to the public repository and/or a second reviewer exists, per the
promotion workflow in `docs/master-brief.md` §15.1 - it's an intentional,
stated gap for the current private-repo phase, not an oversight.

## 12.3 AI-specific rules

- **AI output is not evidence of correctness.** Enforced structurally,
  not just stated: every tool this server exposes is backed by a test
  that exercises real behavior (H2 for what doesn't need DB2's actual
  catalog, per ADR 0009's Tier 1/Tier 2 split; explicitly marked
  unverified where it does), and every quality gate in `mvn verify`
  (ArchUnit, the MCP contract snapshot, the JaCoCo policy-coverage
  threshold) was itself proven able to fail before being trusted - not
  just written and assumed to work. ADR 0005 and ADR 0014 both record a
  case where a gate's first version passed trivially without checking
  anything real, caught only by deliberately breaking the thing it was
  supposed to catch and confirming a real failure.
- **AI must not invent supported Db2 behavior; ambiguous behavior
  requires documentation or integration tests.** Catalog queries in
  `Db2MetadataAdapter` were written against IBM's own documented
  `SYSCAT` column definitions, not recalled from memory - ADR
  0009/`docs/project-baseline-status.md` records this project's history
  of catching and correcting exactly the opposite failure mode (an
  earlier, unrelated AI session inventing a driver-sourcing rationale
  that turned out to be false; corrected in ADR 0003 once questioned).
- **AI must not silently weaken a policy to make a test pass.** No test
  in this repository asserts on a relaxed or mocked-away security check;
  `docs/security/adversarial-test-cases.md` documents two cases where a
  test written *against* the real policy code caught a genuine gap in
  that code (a multi-statement SQL bypass, an unsanitized error message)
  and the fix went into the policy, not the test.
- **Generated tests must include assertions that would fail for an
  unsafe implementation.** The adversarial catalogue above is the
  clearest evidence: each test is paired with a description of the
  specific unsafe behavior it would catch, not just a happy-path
  assertion.
- **Security-sensitive changes require explicit human review.** Every
  change touching `policy/`, `adapter.db2/`, or credential handling in
  this repository's history has been discussed with the maintainer
  before being treated as done, not just committed silently.
- **Dependency additions require purpose, license, maintenance, and
  vulnerability review.** ADR 0003 (Db2 JDBC driver) is the clearest
  example - the original licensing rationale given for the driver choice
  was wrong, the maintainer caught it, and the ADR was rewritten from
  verified facts (Maven Central availability, active release cadence)
  rather than left as originally drafted.
- **Prompts, outputs, or traces containing internal data must not enter
  the public repository.** This is a hard gate on the promotion step
  (`docs/master-brief.md` §15.1). It was exercised for the first time when
  this public edition was created: the published tree was swept for
  credentials, internal hostnames, environment-specific paths and
  internal-only documents before its first commit.

## 12.4 Repository evidence

A real, complete example change lifecycle from this repository's own
history - not a curated illustration written after the fact, but an
actual commit, picked because it shows the workflow catching a genuine
bug rather than rubber-stamping a clean implementation:

```text
Requirement:     Phase 5's "graceful shutdown and cancellation"
                 deliverable (docs/implementation-plan.md, brief section
                 10).

AI proposal:     Investigate what "graceful shutdown" actually requires
                 for a stdio-only MCP server (no HTTP endpoint to drain).
                 Two paths identified: SIGTERM (assumed already correct
                 via Spring Boot's default shutdown hook) and stdin EOF
                 (the normal way an MCP host signals disconnect).

Human decision:  Implicit authorization to proceed via the standing
                 "continue one after another" instruction covering all
                 of Phase 5 - this project's actual review model for
                 solo-maintainer work, not a formal sign-off per item.

Implementation:  Investigating the second path (not assuming it worked)
                 surfaced a real bug: starting the packaged jar with
                 stdin already at EOF left the process running
                 indefinitely - spring.main.keep-alive kept a thread
                 alive independent of MCP session state, and nothing
                 tied the two together. Fixed with a replacement
                 stdioServerTransport bean wrapping System.in in an
                 EOF-detecting stream that triggers a clean
                 SpringApplication.exit().

Tests:           EofTriggeredShutdownInputStreamTest (unit) plus two
                 real packaged-jar scenarios run by hand: stdin EOF from
                 process start, and SIGTERM on a running instance fed by
                 an open pipe (so it wouldn't take the EOF path) -
                 confirming SIGTERM handling was unaffected by the
                 change, not just assumed unaffected.

ADR update:      ADR 0016 written, recording the bug, why it existed,
                 the fix, and the exact verification evidence above -
                 including that every earlier packaged-jar smoke test in
                 this project's history had been pointing at a
                 nonexistent environment variable and never actually
                 exercised a resolvable datasource, a second unrelated
                 finding surfaced by the same investigation and recorded
                 rather than quietly fixed.

Release note:    Commit 6a5aeae, "Exit cleanly when the MCP client
                 disconnects (ADR 0016, Phase 5)" - the commit message
                 itself is the release note for this private-repo phase;
                 a curated CHANGELOG becomes part of Phase 6's public
                 release packaging.
```

No unfiltered prompt archive is published here, and none is planned -
this example was selected and written specifically to demonstrate
engineering control (a real gap found and fixed, verified against the
running artifact rather than assumed) without reproducing this
project's full conversational history, which would be documentation
noise rather than evidence.

## 12.5 Branch workflow: feature-branch → devel → main

> **Applies to the private development repository.** This section
> documents the workflow of the private laboratory repository, where this
> project's changes are made. **This public repository is single-branch:**
> `feature branch → pull request → main`. The private-to-public step is a
> separate, curated promotion described in `docs/master-brief.md` §15.1 —
> not the flow below.

Section 12.2's step 8 ("Merge through a reviewed pull request") is
deliberately spelled out here as its own section, in full, rather than
left implicit — established 2026-09-02/03 after a real collision
between two concurrent AI sessions both pushing to the same shared
branch, and written down here specifically because it had governed
every change to this repository since without ever being documented
anywhere a future session (human or AI) could find it.

**The flow, stated plainly: `feature-branch → devel → main`.** A
GitFlow-style shape - `devel` is the standing integration branch,
`main` is the repository's default/production branch, and no change
reaches `main` except by way of `devel`.

The cycle, per unit of work:

1. **File a GitHub Issue first.** Every backlog item - a bug, a review
   finding, a documented gap - gets its own issue before any code is
   touched. Fixing something and filing the issue afterward is a known
   process failure this project has already made once (issues #34-#36,
   disclosed in ADR 0039 rather than hidden) - not a pattern to repeat.
2. **Branch off `devel`**, named `feature/ISSUE-<number>_<short-slug>`
   (e.g. `feature/ISSUE-55_document-branch-workflow`).
3. Implement, verify locally (`mvnw verify`, relevant scripts).
4. **Open a PR against `devel`.** Wait for the human project owner's
   review before merging - never merge on the AI's own judgment, even
   with CI green.
5. Once approved: **merge into `devel`**, confirm `devel`'s CI is
   green, then close and delete the feature branch.
6. **Do not promote `devel` into `main` after every single issue.**
   Related issues are batched - land each one into `devel`
   individually as above, but hold off on touching `main` until the
   batch is ready. What counts as a "batch" is decided case-by-case
   with the human project owner, not inferred automatically from issue
   similarity.
7. Once a batch is ready: **ask for explicit go-ahead to promote
   `devel` into `main`** in its own PR, separate from every
   feature-branch-into-`devel` merge that preceded it. Confirm `main`'s
   CI is green afterward.

**Multiple AI sessions can work on this repository concurrently**
(this project has used both a local Claude Code CLI session and a
cloud session in parallel) - the collision that established this
workflow happened because two sessions pushed to the same shared
branch without either knowing the other was active. Before pushing to
a shared branch (`devel` or `main`), check for other active sessions
and coordinate through the human project owner if one is found, rather
than assuming exclusive ownership of a branch name.

## Cross-model architecture, implementation and review

`docs/adr/0038-human-governed-cross-model-ai-engineering.md` formalizes a
second, distinct AI role this project uses on top of §12.1-12.4's
single-agent workflow: a separate model (ChatGPT) supporting target
architecture, master-brief direction, and security/portfolio strategy, and
performing an independent implementation/compliance review of the
resulting repository — with Claude Code (the implementation agent above)
validating every finding against the real codebase before anything from
that review is acted on.

Three distinct responsibilities:

- **Human project owner — Marco.** Requirements, priorities, architectural
  decisions, acceptance of findings and risk, and final approval. Every
  step below terminates here, not in either AI's output.
- **ChatGPT.** Target-architecture support, master-brief development,
  security/portfolio strategy, and a separate implementation/compliance
  review of the actual repository against the brief and stated security
  requirements. No production-code write access to this repository.
- **Claude Code.** Implements the approved direction as production code,
  tests, ADRs, and technical documentation; investigates every review
  finding against the actual codebase before recording a disposition.

The workflow:

1. The human project owner defines a requirement or idea.
2. ChatGPT helps develop the target architecture, constraints, and a
   master-brief-level plan.
3. The human project owner approves or adjusts the resulting direction.
4. Claude Code implements the agreed scope, with automated verification.
5. ChatGPT reviews the resulting repository against the brief,
   architecture, and stated security requirements — reading the actual
   repository, not a summary of it.
6. Claude Code validates every finding against the real code, classifying
   each as `Confirmed`, `Rejected with evidence`, `Accepted risk`, or
   `Deferred` — each backed by a specific file, line, or test.
7. The human project owner decides acceptance.

**This is explicitly not an independent human security audit**, and using
two models does not eliminate correlated model error — both reviewing and
implementing roles above are AI systems, and either can share the same
blind spot or the same tendency to trust a plausible-sounding conclusion
over the artifact it describes. Neither AI holds final decision authority;
step 7 is not a formality. Step 6 requires inspecting actual source and
tests, never a review classification based on the implementation agent's
own summary of what it built — the same "AI output is not evidence of
correctness" principle from §12.3, applied to review claims as much as to
implementation claims. Any implementation claim entering this workflow
must distinguish unit/H2 verification, live-Db2 (or other live external
system) verification, and unverified assumption, per ADR 0009's Tier
1/Tier 2 split.

Every review is kept as two separate, permanent documents: the review as
received (never silently rewritten to look resolved) and a separate
response document recording each finding's classification and supporting
evidence — see `docs/reviews/2026-09-01-master-brief-gap-review.md` and
`docs/reviews/2026-09-01-master-brief-gap-review-response.md` for the
first full example, and ADR 0038 for the complete decision record.

## Known environment constraint: AI sessions can push branches, not tags

Recorded here for future AI sessions working in this environment, so
this doesn't need rediscovering: a Claude Code remote-environment
session in this project can push commits to its working branch, but
**cannot push git tags or create GitHub releases** - not a token-scope
or repository-configuration issue, verified directly rather than
assumed. `git push origin refs/tags/v0.1.0` failed with HTTP 403; a
direct call to GitHub's REST API to create the same ref
(`POST /repos/.../git/refs`) got an explicit, unambiguous denial from
the session's own outbound proxy: `"Write access to this GitHub API
path is not permitted through this proxy."` A second test with a
non-version-shaped tag name failed identically, ruling out a `v*`
tag-protection rule on the repository as the cause - this is a blanket
restriction on ref/tag creation through the proxy, not a rule about
this specific tag name.

Practical consequence: an AI session can prepare everything a tagged
release needs (a `CHANGELOG.md` entry, a clean `mvn verify`, a green
CI run) and create the tag locally, but actually publishing that tag to
GitHub requires either the GitHub web UI (Releases → Draft a new
release, which creates the tag as part of publishing) or a `git push`
from an environment with unrestricted push access - a human step, not
something to keep retrying from inside the session.
