# Changelog

All notable changes to this project are documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versioning follows
[Semantic Versioning](https://semver.org/).

## [1.0.1] - 2026-10-11

Licensing, packaging and dependency-security release. **No functional
change**: no tool, policy, limit, validation rule or observability behaviour
was altered.

Jackson dependencies were updated to address security findings reported by
the project's SBOM vulnerability scan. The same scan reports findings
identically on the `1.0.0` commit, so they affect that release too; they are
not introduced by this one. See **Security** below.

### Security

- **Jackson dependency security updates**, prompted by the CycloneDX SBOM
  vulnerability gate introduced in `1.0.0`:

  | Artifact | From | To | Advisories |
  |---|---|---|---|
  | `com.fasterxml.jackson.core:jackson-core` | 2.21.5 | 2.21.7 | CVE-2026-89407, CVE-2026-89425 |
  | `com.fasterxml.jackson.core:jackson-databind` | 2.21.5 | 2.21.7 | CVE-2026-68497, CVE-2026-91776, CVE-2026-91777 |
  | `tools.jackson.core:jackson-core` | 3.2.2 | 3.2.3 | CVE-2026-89425 |
  | `tools.jackson.core:jackson-databind` | 3.1.5 | 3.2.3 | CVE-2026-68497 |
  | `tools.jackson.dataformat:jackson-dataformat-yaml` | 3.1.5 | 3.2.3 | — (moved with the BOM) |

  The Jackson 3 artifacts are now governed by a single `jackson-bom.version`
  property at `3.2.3` instead of a standalone `jackson-core` pin. That pin
  existed to escape a real binary incompatibility and left core on `3.2.x`
  while databind stayed on `3.1.x`; one BOM version removes the skew rather
  than patching around it.

  The Jackson 2 line arrives transitively through
  `com.github.victools:jsonschema-generator` and was managed by nothing —
  Spring Boot 4.1 moved to Jackson 3 — which is how it drifted to a
  vulnerable version unnoticed. It is now pinned explicitly.

### Removed

- **Four third-party libraries are no longer distributed**, because their
  licences could not be verified or carried an unexpected obligation
  ([ADR 0049](docs/adr/0049-no-unverifiable-third-party-licences.md)):
  `org.antlr:ST4:4.3.4`, declared **BSD-4-Clause** — the variant with the
  *advertising clause*, which would oblige every advertising material
  mentioning the software's features to carry an acknowledgement;
  `org.antlr:antlr-runtime:3.5.3`, declared only as `"BSD licence"` with no
  SPDX identifier and no licence text anywhere, so the governing variant is
  not established; `aopalliance:aopalliance:1.0`, declared `"Public Domain"`
  with no URL or text, which is not a licence and varies by jurisdiction;
  and `spring-ai-template-st`, the Spring AI prompt-template module that
  brought the two ANTLR components in and that this project never used.
  102 bundled libraries, down from 106. **No functional change** — verified
  against a live Db2 with zero `ClassNotFoundException`. No shipped
  component now carries an unverified licence or an advertising clause.

### Changed

- **Licence for original project material is now
  [PolyForm Strict 1.0.0](LICENSE)**, reproduced verbatim from the PolyForm
  Project with no clause added, removed or altered. It permits use for any
  noncommercial purpose - including by charitable, educational, public
  research, public safety or health, environmental and government
  organizations - and does not grant rights to modify or redistribute.
  Release `1.0.0` was Apache-2.0; those rights were granted and are not
  withdrawn. See [`LICENSE-HISTORY.md`](LICENSE-HISTORY.md).
- **The IBM Data Server Driver for JDBC and SQLJ is not distributed.** The
  `1.0.0` build produced an application jar that contained
  `jcc-12.1.5.0.jar`; there is no known external distribution of such a jar,
  but one would have carried the driver as soon as it was published. The
  driver is now declared at `test` scope *and* explicitly excluded from the
  repackaged archive, and must be supplied by the operator under IBM's own
  terms
  ([ADR 0048](docs/adr/0048-external-jdbc-driver.md)).
- **The application jar now launches through `PropertiesLauncher`**
  (`layout` `ZIP`), so the externally supplied driver can be added with
  `-Dloader.path`. The previous `JarLauncher` has no equivalent mechanism.
  Operator instructions:
  [`docs/operations/external-jdbc-driver.md`](docs/operations/external-jdbc-driver.md).

### Added

- [`ENTERPRISE-EVALUATION.md`](ENTERPRISE-EVALUATION.md) - a supplementary
  permission granting commercial organizations the right to evaluate the
  unmodified software in a non-production environment, with no time limit.
  It adds rights alongside the licence and amends nothing in it.
- [`NOTICE`](NOTICE) - licensor identification and the licence stack.
- [`SOURCE-AVAILABILITY.md`](SOURCE-AVAILABILITY.md) - the **EPL-2.0 section
  3.1(a)** source-availability statement for `logback-classic`,
  `logback-core` and `jakarta.annotation-api`, with each component's
  `sources` artifact pinned by size and SHA-256. An earlier draft of this
  release wrongly concluded that the elected licences created no
  source-availability obligation; EPL-2.0 applies on distribution *in any
  form*, not only to modified copies.
- [`third-party-licenses/`](third-party-licenses/) - the licence texts
  required by components whose own artifact carries none (47 of the 102
  shipped libraries), each obtained from upstream pinned to that component's
  release tag, with provenance and checksums in `MANIFEST.json`.
- **All licence and attribution material now ships inside the application
  jar** under `META-INF/legal/`, so a bare jar download carries its own
  compliance material.
- `scripts/verify-no-jcc-cases.py` - 15 committed evasion cases for the
  no-JCC gate, run by `verify-no-jcc.sh --self-test` in CI. The gate had
  passed `release.zip -> app.jar -> lib/innocent.jar -> DB2Driver.class`
  before this.
- [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md) - assembled attribution
  notices for distributed third-party components, collected from the
  artifacts themselves rather than from SBOM licence templates.
- [`LICENSE-HISTORY.md`](LICENSE-HISTORY.md) - which licence governed which
  release.
- `scripts/verify-no-jcc.sh` - fails the build if any IBM driver reappears
  in a shipped artifact. Checks every distributable archive under `target/`
  and opens nested jars, so a driver shaded into a bundled library is caught
  too. Wired into CI and `scripts/verify-release.sh`.
- [ADR 0049](docs/adr/0049-no-unverifiable-third-party-licences.md) - the
  rule behind ADR 0048, generalised: a component whose redistribution terms
  cannot be verified is not distributed.
- [ADR 0048](docs/adr/0048-external-jdbc-driver.md) - external JDBC driver
  and licence change.

## [1.0.0] - 2026-09-21

First public release. This is the curated public edition of a private
development repository; its history begins here deliberately, as described in
[`docs/master-brief.md`](docs/master-brief.md) section 15.1.

### Added

- **MCP server over stdio** exposing a governed, read-only tool set against
  IBM Db2: schema and table metadata, reference-data lookups, procedure
  description and source, query execution, and EXPLAIN-plan acquisition.
- **Capability profiles** (`METADATA_ONLY`, `SAFE_QUERY`, `ADVANCED_QUERY`)
  selecting which tools are exposed and under which limits, defaulting to
  `METADATA_ONLY` (ADR 0006, ADR 0032, ADR 0033).
- **Defense-in-depth read-only enforcement** at three independent points, so
  no single check is the only thing standing between a client and a write
  (ADR 0002).
- **Structural SQL validation** over a parsed statement tree rather than
  string matching, with a schema allowlist applied after parsing
  (ADR 0007, ADR 0008).
- **Query complexity and cost controls** - row caps, statement timeouts, join
  and predicate limits, differentiated per capability profile
  (ADR 0034, ADR 0032).
- **A hard bound on response payload size**, measured against the complete
  serialized response rather than row content alone, failing closed
  (ADR 0045).
- **Traceability and observability**: correlation IDs across audit log,
  metrics and spans; structured audit events; OpenTelemetry spans; optional
  Dynatrace export (ADR 0010, ADR 0011, ADR 0012, ADR 0036, ADR 0041).
- **Structured error model** with stable categories and no stack traces
  crossing the MCP boundary (ADR 0018).
- **Hexagonal ports** (`MetadataPort`, `QueryPort`, `ExplainPort`,
  `CapabilityPort`) with a database-free fake adapter exercising all backed
  tool methods end to end (ADR 0027, ADR 0028, ADR 0029).
- **47 Architecture Decision Records** in [`docs/adr/`](docs/adr/), recording
  each decision with its context, alternatives and verified consequences.
- **A CycloneDX SBOM** generated on every `package`, so the dependency set
  is machine-readable for supply-chain review.
- **Security static analysis** with Find Security Bugs, run at promotion and
  compared against an approved baseline by finding *identity* rather than
  count, so a known finding cannot be silently replaced by a new one
  (ADR 0046).
- **Threat model and adversarial test cases** in
  [`docs/security/`](docs/security/).
- **A reproducible ITSM demo** with schema, fixtures and guided scenarios in
  [`docs/demo/`](docs/demo/).
- **CI** running build, tests, static analysis and architecture tests, plus a
  scheduled integration suite against a real Db2 container.
