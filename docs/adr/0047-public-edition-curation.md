# ADR 0047: How the public edition is curated

## Status
Accepted — the record of the decisions that produced this repository's
first public release.

## Date
2026-09-21.

## Context

This repository is the **public edition** of a project developed in a
private laboratory repository. Master brief §15.1 requires that the public
repository have its own clean Git history, and forbids creating it by
making the private repository public, forking it, or publishing its history
with internal files deleted — because deleting a file from the working tree
does not remove it from earlier commits.

That requirement is satisfied. But it leaves a gap this ADR exists to
close: **the curation itself was a set of decisions, and none of them were
recorded anywhere a reader of this repository could find.** For a project
whose stated value is that every significant decision is written down with
its reasoning, the most consequential decisions about the artefact in front
of the reader were the only undocumented ones.

There is also a mechanical reason the record has to live here, as a
document, rather than in a tracker. Issues and pull requests do not survive
the private-to-public boundary: this repository's history begins at a single
commit, so there is nothing for an issue number to attach to, and the
private tracker is not readable from here. Documents are the only artefacts
that make the crossing.

## Decision

### 1. The first commit is a curated snapshot; promotion is normal thereafter

The public history begins with one commit containing the curated tree. It
was produced by extracting the tracked files from the private repository's
`main` (`git archive`, so no `.git`, no ignored files, nothing stale),
curating them, and committing once.

Brief §15.1 describes per-feature promotion by `git cherry-pick
--no-commit`. That workflow governs everything **after** the first commit;
it cannot bootstrap an empty repository, and using it would require adding
the private repository as a remote, which pulls private objects into the
public clone — precisely the risk §15.1 exists to prevent.

**From the second commit onward, this repository has ordinary history.**
Future promotions are branches and pull requests here, reviewable and
permanent. The single-commit start is the documented first step of that
process, not a gap in it.

### 2. The requirements brief is published, trimmed

35 of the 46 ADRs cite the master brief, frequently in a load-bearing way —
the *reason* for a decision is often the requirement it satisfies. Removing
those citations would have meant editing three quarters of the ADR corpus
and stripping exactly the reasoning the ADRs exist to show.

So the brief is published, with sections 2, 20 and 22 removed: portfolio
positioning, suggested CV wording, and a closing strategic recommendation.
None of the three is cited anywhere.

**Section numbering is deliberately preserved**, with gaps, so that every
existing citation still resolves. A note at the top of the brief says so.

### 3. Documents are cut only when nothing points at them

An earlier curation pass removed eight documents and left roughly thirty
files with dangling references. The project's documentation is a dense
citation graph, and that density is what makes its traceability claim real;
cutting nodes strands edges.

The rule adopted instead: **publish nearly intact, cut only what nothing
references.** Exactly one file was removed on that basis — private tooling
instructions with no inbound references.

### 4. The checked-in workflow is authoritative over older ADR text

The private repository uses `feature branch → devel → main`. This one is
single-branch. The workflows were retargeted accordingly: the previous
guards required a `devel` head branch, which in a repository without one
means the jobs would never have run at all.

Rather than rewrite dated decisions, ADR 0046, the AI-development policy and
the threat model carry explicit public-edition notes stating what this
repository actually does and that **where the text and the checked-in
workflow disagree, the workflow is authoritative.**

### 5. ADRs and review records keep their original tense

They describe the state at their date. ADR 0046 says the public repository
does not exist yet; that was true when it was written. Rewriting history to
match the present would destroy the record's value as a record.

Living documents — the README, the implementation plan, the development
policy — were corrected, because they describe the present.

### 6. What was genericised, and why

A second, still-private project was named in ten places as the motivating
future consumer of the portability extension (ADR 0021). The name is
replaced by *"a planned AI incident-analysis client"*: the architectural
justification is preserved, without publicly announcing a project that does
not yet exist. The real name returns when that project is public.

Links into the private issue tracker were converted to plain text. **Issue
numbers themselves remain** as traceability markers; they will not resolve
for an outside reader, and that is an accepted cost of keeping the trail
intact.

### 7. Regulatory statements are alignment claims, never conformity claims

An earlier draft of the README asserted that artefacts here *meet* BSI
technical guidelines and *are* what regulated operators require. That
overstated what this repository establishes.

The published wording is scoped: these artefacts are intended to support
selected practices described in BSI TR-03183-2 and TR-03185, and that is an
alignment statement, not a certification, conformity assessment or legal
opinion. Applicability of the CRA, NIS2 or the GDPR depends on actor,
distribution model, use context and applicable law — none of which this
repository determines.

## Consequences

- A reader who notices the single-commit history finds the reason here,
  in the same idiom as every other decision in the project.
- The brief's section numbering has visible gaps. That is the deliberate
  cost of keeping 35 ADRs' citations valid, and is cheaper than the
  alternative.
- Issue references in older documents are opaque to public readers. The
  reasoning they point at is published; the workflow around it is not.
- Because this is the record of a completed curation, it dates quickly.
  Future promotions get their own history and do not need to amend it.
