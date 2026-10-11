# ADR 0043: Remaining CI quality gates - Enforcer, Spotless, SpotBugs (#42)

## Status
Accepted

## Date
Proposed 2026-09-08, Accepted 2026-09-08.

## Context
P1-5 of the 2026-09-01 master-brief gap review (issue #42) named four
missing quality gates: Maven Enforcer, a formatter (Spotless/Checkstyle),
a static-analysis tool (SpotBugs/PMD), and a dependency-vulnerability
scan. The dependency-vulnerability scan was addressed first and separately
(Trivy scanning a CycloneDX SBOM, documented inline in `pom.xml` and
`docs/security/threat-model.md`'s "Dependency-vulnerability monitoring"
section) - the human project owner explicitly deferred the other three
rather than bundling all four into one change, then later asked for them
to actually be built rather than left open indefinitely. This ADR covers
those remaining three.

Each gate was run against this actual codebase, unfiltered, before any
suppression or exclusion was written - matching this project's own "test
the claim, don't assume" convention (ADR 0005/0014 both record a coverage
gate's first version passing trivially until deliberately broken to prove
it actually checks something; the same discipline applies here in
reverse - don't suppress a finding before confirming it isn't real).

## Decision drivers
- Enforcer's `dependencyConvergence` rule was tried and rejected: it flags
  this project's own intentional BOM-override properties (`tomcat.version`,
  `jackson-core` - ADR 0042/#42's own tomcat CVE fix among them) as
  convergence errors, punishing the exact pattern this project already
  uses correctly. Enforcing it would mean either reverting real, verified
  fixes or drowning genuine future convergence bugs in expected noise.
  `requireUpperBoundDeps` was kept instead - it confirms an override
  actually takes effect, which is the property this project actually
  wants guaranteed.
- A full AST reformatter (Google/Palantir Java Format) was tried and
  rejected for Spotless: this codebase's existing hand-formatting
  (wrapped method chains, aligned comments explaining *why*) doesn't
  match any standard formatter's output, and a full reformat would touch
  every file in the project for zero behavioral benefit while drowning
  real, reviewable changes in reformatting noise on every PR afterward.
  Scoped instead to mechanical, low-risk checks: import order, unused-
  import removal, trailing whitespace, final newline.
- Spotless's line-ending check needed explicit `GIT_ATTRIBUTES`
  configuration, not its own default - without it, every text file
  appeared to violate formatting on this (Windows, `core.autocrlf=true`)
  development machine, even though the actual committed content already
  matched `.gitattributes`' declared `eol=lf` for every file (confirmed
  directly: cloning fresh with `core.autocrlf=false` and re-running
  `spotless:check` against that clean checkout found only 6 files with
  genuine violations, not the ~52 the first, misconfigured run reported).
  A real, previously-latent local-checkout/`.gitattributes` staleness
  this investigation surfaced, not something to leave unexplained.
- SpotBugs findings were triaged individually, not bulk-suppressed:
  8 of 16 findings (`QueryResult`/`ProcedureDetail`/`ExplainPlanResult`
  exposing their internal `List`s directly) were genuine and got fixed
  for real (each record's compact constructor now calls `List.copyOf(...)`),
  not excluded - these are the actual MCP-facing typed result records, and
  a caller holding the same list reference passed into the constructor
  could otherwise mutate what's supposed to be an immutable result. The
  remaining 8 were confirmed to be either a well-known dependency-
  injection false-positive pattern or an already-intentional design
  decision (see Decision below), and only those got exclude-filter
  entries, each with a specific justification.

## Decision

### Maven Enforcer
`requireJavaVersion` (`[21,)`), `requireMavenVersion` (`[3.9,)`),
`banDuplicatePomDependencyVersions`, `requireUpperBoundDeps` - bound to
the `enforce` goal's own default `validate` phase, no explicit binding
needed. All four passed cleanly against this project's current `pom.xml`
on the first run.

### Spotless
`spotless-maven-plugin`, scoped to `removeUnusedImports`, `importOrder`,
`trimTrailingWhitespace`, `endWithNewline`, `lineEndings: GIT_ATTRIBUTES`.
Bound to `verify`. First real (post-line-ending-fix) run found 6 files
with genuine import-order violations - fixed via `spotless:apply`, no
manual editing needed.

### SpotBugs
`spotbugs-maven-plugin`, `effort=Max`, `threshold=Medium`, bound to
`verify`. First unfiltered run found 16 findings across four categories:

- **8 findings, genuinely fixed**: `QueryResult.columns`/`.rows`,
  `ProcedureDetail.parameters`, `ExplainPlanResult.operators` each
  exposed their constructor-provided `List` directly (`EI_EXPOSE_REP`/
  `EI_EXPOSE_REP2`). Each record's compact constructor now wraps the
  field in `List.copyOf(...)` - genuine immutability, not just satisfying
  the tool.
- **5 findings, excluded (`spotbugs-exclude.xml`)**: `EI_EXPOSE_REP2` on
  five constructor-injected framework collaborators (`Db2CapabilityAdapter.dataSource`,
  `MetricsRecorder.registry`, `Db2ConnectivityChecker.dataSource`,
  `SerializedStdioServerTransportProvider.jsonMapper`/`.outputStream`) -
  the well-known false-positive category for dependency injection: a
  `DataSource`/`MeterRegistry`/`OutputStream` is a shared, singleton-scoped
  collaborator, not a value type a caller could meaningfully mutate out
  from under this class, and there is no meaningful "defensive copy" of a
  connection pool or a stream.
- **1 finding, excluded**: `DM_EXIT` on `StdioLifecycleConfig` -
  `System.exit()` there is the entire point of ADR 0016's graceful-shutdown
  design, not something to avoid.
- **2 findings, excluded after investigation, not blind suppression**:
  `RV_RETURN_VALUE_IGNORED_NO_SIDE_EFFECT` on `SqlComplexityValidator$ComplexityWalker`'s
  `visit(Values, S)`/`visit(TableStatement, S)` overrides. Investigated
  directly rather than assumed safe, given this is security-relevant
  policy code: both node types genuinely have no join/nesting structure
  of their own to walk for complexity purposes (`TableStatement` is
  JSqlParser's umbrella type for statement kinds this walker already
  handles via more specific overrides; `Values` is a literal list with no
  nested substructure) - explanatory comments added directly above each
  override in `SqlComplexityValidator.java` itself, not just in the
  exclude filter.

## Explicitly out of scope
- **Checkstyle, PMD.** The review named these as alternatives to Spotless/
  SpotBugs respectively, not additions to them - one formatter and one
  static-analysis tool is the gate this finding asked for, not four
  overlapping tools.
- **A full AST reformat of the existing codebase.** See Decision drivers -
  a deliberate scope boundary, not a deferred cleanup.
- **`dependencyConvergence` (Enforcer).** See Decision drivers - actively
  rejected, not merely unconfigured.

## Consequences
- `pom.xml` gained three new plugins and a new `spotbugs-exclude.xml`
  file at the repo root.
- No change to `.github/workflows/ci.yml` - all three gates bind to
  Maven phases (`validate`/`verify`) the existing `mvnw verify -DskipITs`
  CI step already runs; unlike the Trivy/SBOM scan (which needed a
  distinct step operating on a generated file post-`package`), these
  three needed no new CI step of their own.
- Three domain records (`QueryResult`, `ProcedureDetail`,
  `ExplainPlanResult`) are now genuinely immutable, not just
  record-shaped - a real hardening, not a tooling-satisfaction exercise.
- `SqlComplexityValidator.java` gained two explanatory comments confirming
  a deliberate design choice that was previously undocumented in the
  source itself (only inferable from the surrounding visitor-pattern
  structure).
- Issue #42 (P1-5) is now fully closed - all four originally-named gates
  exist: dependency-vulnerability scanning (Trivy/SBOM, prior change),
  and now Enforcer, Spotless, SpotBugs (this change).

## Verification / enforcement
`./mvnw verify -DskipITs` - full suite green, all four gates (Enforcer,
Spotless, SpotBugs, plus the pre-existing JaCoCo/ArchUnit/contract-snapshot)
passing together. `spotbugs:check` re-run standalone after each fix/exclusion
to confirm the specific finding it targeted actually cleared, not just
that the aggregate build passed. No test mutates a
`QueryResult`/`ProcedureDetail`/`ExplainPlanResult` accessor's returned
list after construction (confirmed by grep) - the new `List.copyOf`
immutability introduces no regression risk to existing tests.

## Related decisions
ADR 0005 (ArchUnit, the "prove a gate can fail before trusting it"
convention this ADR's own findings-triage follows), ADR 0014 (JaCoCo
policy-coverage gate, same convention), the prior dependency-vulnerability
scan work (Trivy/SBOM, `pom.xml`'s `cyclonedx-maven-plugin` comment and
`docs/security/threat-model.md`'s "Dependency-vulnerability monitoring"
section) - the other three-quarters of P1-5/#42, addressed first and
separately.
