# ADR 0046: Run security static analysis at promotion, not on every push (#86)

## Status
Accepted

## Date
Proposed 2026-09-13, Accepted 2026-09-13.

## Context

SpotBugs has run on every push since [ADR 0043](0043-remaining-ci-quality-gates.md), alongside Enforcer and Spotless. But SpotBugs is a correctness and bytecode-hygiene analyser, not a security scanner. The project's security tooling until now covered the supply chain and secrets - Trivy against an SBOM, and gitleaks - and nothing statically analysed *this project's own code* for injection-class defects.

That is a conspicuous gap for a project whose entire thesis is governed access with an untrusted LLM. `executeQuery` executes caller-influenced SQL by design. `Db2MetadataAdapter.executeLookup` concatenates caller-supplied identifiers into dynamic SQL, which [ADR 0002](0002-read-only-enforcement.md) layer 3 states explicitly. The master brief additionally forbids using "secure" or "production-ready" without qualification and evidence - so an unexamined codebase asserting it handles SQL safely is exactly the claim the brief rules out.

[Find Security Bugs](https://find-sec-bugs.github.io/) is the standard SpotBugs extension for this, loaded through `spotbugs-maven-plugin`'s own `<plugins>` block.

## What the reconnaissance actually found

Before deciding anything, FSB 1.14.0 was run once against this codebase (issue #86 carries the full output). **Five findings, zero vulnerabilities.**

| Location | Detector | Why it is already defended |
|---|---|---|
| `Db2MetadataAdapter:505,509` | `SQL_INJECTION_SPRING_JDBC` | `requireIdentifier()` validates schema, table, codeColumn, descriptionColumn and filterColumn against `^[A-Za-z0-9_#$@]+$` at lines 365-370, before the statement is built; the filter *value* is bound with `?`. FSB cannot see the check because it happens in a different method. |
| `Db2QueryAdapter:194` | `SQL_INJECTION_JDBC` | The product's purpose - caller SQL, deliberately executed, behind the five verified gates below. |
| `Db2QueryAdapter:601` | `SQL_INJECTION_SPRING_JDBC` | Same `sql`, same five gates (verified separately, not assumed). `queryTag` derives from a server-generated UUID. |
| `Db2QueryAdapter:603` | `SQL_INJECTION_SPRING_JDBC` | Concatenated from internal constants; flagged for being non-constant, not for carrying caller input. |

Nothing was reported by the crypto, command-injection, path-traversal, XXE or deserialization detectors. That is meaningful negative evidence, not merely absence of output.

### The guard chain behind findings 3 and 4, verified

An earlier draft of this ADR asserted these two were defended by "ADR 0002's three-layer chain plus structural, allowlist and complexity validation" without checking. That is exactly the unqualified security claim the master brief bans, so it was traced properly. Both methods run the **same five gates, in the same order, on the same `sql`**:

| # | Gate | `executeQuery` | `explainQuery` |
|---|---|---|---|
| 1 | Capability profile | `requireQueryExecutionAllowed` | `requireExplainPlanAllowed` |
| 2 | `requireReadOnlyStatement(sql)` | yes | yes |
| 3 | `structuralValidator.validate(sql)` | yes | yes |
| 4 | `schemaAllowlistValidator.validate(sql)` | yes | yes |
| 5 | `complexityValidator.validate(sql)` | yes | yes |

The checks reach execution at `Db2QueryAdapter:194` and, via `captureAndReadPlan`, at `:601`.

One guarantee is stronger than the gate list itself: in both methods `sql` is captured by a lambda, so Java requires it to be effectively final. **The string that is validated is provably the string that is executed** - the compiler forbids reassignment between gate and execution, so no code change can quietly introduce a gap there without failing to compile.

Compatibility was also confirmed in the same run: FSB **1.14.0** works with `spotbugs-maven-plugin` **4.10.4.1**.

## Decision

**Security static analysis runs at promotion and on demand, not on every push.** The decision here is the *trigger*, not the tool.

### What "promotion" means here

This project uses the word for two different events, so this ADR states which one it means:

1. **`devel` -> `main`, inside this private repository** - the feature-branch workflow documented in [`docs/ai-development/development-policy.md`](../ai-development/development-policy.md) (issue #55). **This is what this ADR and `security-scan.yml` mean**, implemented as `pull_request: branches: [main]`.
2. **Private repository -> public repository** - what `docs/master-brief.md` §15.1 calls promotion: the controlled, sanitized cherry-pick of mature capabilities into a separate public repo with its own clean history.

> **Public-edition note (superseding, 2026-09-21).** This ADR was written in, and describes, the private development repository. **In this public repository there is no `devel` branch.** The checked-in `.github/workflows/security-scan.yml` runs on `workflow_dispatch` and on **any pull request targeting `main`**, excluding Dependabot by actor rather than by head branch. Where this ADR's text and that workflow disagree, **the workflow is authoritative**; the text below is preserved as the record of why the trigger was chosen, not as a specification of the control now in force.

Sense (2) is arguably the more consequential security moment, since that is when code becomes world-visible. **It was not covered by this ADR and was unautomated when it was written**, because the public repository did not exist yet. When it does, whether this scan (or a stricter, blocking variant of it) gates that promotion is a separate decision, and should get its own ADR rather than being read into this one.

1. **A Maven profile, not the default build.** FSB lives in `<profile><id>security-scan</id>`. `mvn verify` is untouched; `mvn verify -Psecurity-scan` runs the scan.
2. **A separate workflow** (`.github/workflows/security-scan.yml`) on the `devel` -> `main` promotion PR and on `workflow_dispatch`, guarded to PRs from `devel` so Dependabot's main-targeted PRs do not trigger a source-code scan they have no use for.
3. **Report-only, compared by finding identity.** The scan never fails a build. It diffs the run's findings against a checked-in `security-baseline.txt` by **identity** - detector, class and method - and reports anything outside it, plus any baseline entry that has stopped appearing. The XML report is retained as an artifact.

   The first version compared only the **count** against a baseline of five. The 2026-09-17 re-review (R2-P1-6) correctly showed this cannot detect substitution: one known finding disappearing while one new vulnerability appears leaves the total at five, and the job reported "at baseline". Corrected under #93, with a test scenario demonstrating the old check passing and the new one flagging both the arrival and the disappearance.

   Identity deliberately excludes line numbers and normalises javac's synthetic lambda indices, because both move on edits that have nothing to do with security - churn that would train the reader to ignore the report.

### Why not an every-push gate, like ADR 0043's three

This is a deliberate departure from ADR 0043, and the reason is the five findings above.

As a blocking per-push gate, FSB would fail on all five. Making it pass would require five entries in `spotbugs-exclude.xml` - and **an exclusion suppresses the next finding at that location as effectively as the current one.** An exclusion on `Db2QueryAdapter:194` silences today's known-safe report *and* a genuinely dangerous change introduced there next year. For the specific lines where this project's security argument lives, that is the worst possible place to install a permanent blind spot.

Running at promotion instead keeps the signal and needs no exclusions at all. The baseline is a reviewed list of five defended findings, each with its justification recorded in `security-baseline.txt` itself; anything outside that list is what a human reads at promotion time.

### Why report-only rather than failing on a threshold

A failing threshold would have to be maintained as the code legitimately changes, and would break builds for reasons that require human interpretation anyway. The genuinely useful question - *is this new finding defended, or is it real?* - cannot be answered automatically. So the workflow reports identities against the approved baseline and leaves the judgement where it belongs.

Updating `security-baseline.txt` is therefore a deliberate act that belongs in a reviewed diff. The file says so in its own header: do not regenerate it to make a build quiet.

## Consequences

- **The everyday build is unchanged.** No new per-push cost, and FSB's slower release cadence cannot block a build it is not part of - relevant given the SpotBugs 4.9 -> 4.10 bump broke `devel` on a genuine finding (#83) only days earlier.
- **No exclusions are created**, so no location is permanently silenced.
- **The five findings remain reported at every promotion.** That is intentional: they are a standing description of where this project's security argument has to hold, re-asserted each time anything is promoted, rather than suppressed once and forgotten.
- **Regression protection is the actual value, not discovery.** FSB found no vulnerability. Its worth is that a *new* unparameterized SQL path added later shows up as an identity outside the approved baseline at the next promotion - protecting precisely the defect class that would invalidate this project's central claim.
- A count above five requires human triage. If a future finding is genuine, it gets fixed; if it is defended, the defence is recorded here and the baseline updated - not excluded in a filter file.
- This ADR is what stops the inconsistency with ADR 0043 from being "corrected" later by someone who sees FSB outside the gate chain and assumes it was an oversight.
