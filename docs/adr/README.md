# Architecture Decision Records

This directory tracks the key design decisions behind the DB2 MCP Server —
why it's built the way it is, not just what it does.

Numbers are assigned sequentially in the order decisions were actually
made — standard ADR convention, and the only scheme that doesn't require
reserving numbers for topics that might get decided later. An earlier
version of this scheme tried to mirror the master brief's own numbered
topic list (`docs/master-brief.md` §13.1) 1:1, which produced gaps for
brief topics not yet written and out-of-order numbers for project-specific
decisions layered on top. That's been abandoned: **ADR numbers here have
no relationship to the brief's numbering** — cross-reference by title, not
by number, when comparing the two documents.

| ADR | Title | Status |
|-----|-------|--------|
| [0001](0001-stdio-transport.md) | Use stdio transport, not HTTP | Accepted |
| [0002](0002-read-only-enforcement.md) | Enforce read-only access at three independent layers | Accepted |
| [0003](0003-driver-sourcing.md) | Source the DB2 JDBC driver from Maven Central, pinned to a validated version | Accepted - licensing claims corrected, bundling superseded by [0048](0048-external-jdbc-driver.md) |
| [0004](0004-credentials-env-vars.md) | Pass credentials via environment variables, not a vault | Accepted |
| [0005](0005-layer-separation.md) | Separate transport, application, and Db2 adapter layers via ports, enforced by ArchUnit | Accepted |
| [0006](0006-capability-profiles.md) | Expose capabilities through explicit security profiles (`metadata-only`/`safe-query`/`advanced-query`) | Accepted |
| [0007](0007-sql-structural-validation.md) | Validate SQL structurally and enforce resource limits | Accepted |
| [0008](0008-schema-allowlist.md) | Enforce a schema allowlist for `executeQuery`, deny by default | Accepted |
| [0009](0009-testing-without-live-db2.md) | Verification strategy in the absence of a live DB2 instance | Accepted |
| [0010](0010-traceability.md) | Provide end-to-end traceability without retaining sensitive data | Accepted |
| [0011](0011-observability-metrics.md) | Observability via Micrometer, with no exposed HTTP/JMX endpoint | Accepted |
| [0012](0012-opentelemetry-spans.md) | OpenTelemetry spans for the executeQuery pipeline | Accepted |
| [0013](0013-mcp-contract-snapshot-tests.md) | Guard the MCP tool contract with a golden snapshot test | Accepted |
| [0014](0014-jacoco-policy-coverage-gate.md) | JaCoCo coverage gate on the policy package | Accepted |
| [0015](0015-config-validation-and-effective-config-report.md) | Validate configuration at startup and report it safely | Accepted |
| [0016](0016-graceful-shutdown.md) | Exit cleanly when the MCP client disconnects | Accepted |
| [0017](0017-ai-driven-development-governance.md) | Adopt AI-driven, human-governed development | Accepted |
| [0018](0018-structured-error-model.md) | A structured error model for MCP tool failures | Accepted |
| [0019](0019-health-and-readiness.md) | Health and readiness for a stdio-only server | Accepted |
| [0020](0020-lookupRefData-policy-and-metadata-error-sanitization.md) | Close two P0 findings from external code review — lookupRefData policy bypass and unsanitized metadata errors | Accepted |
| [0021](0021-database-portability-extension-adoption.md) | Adopt the database-portability master brief extension | Accepted |
| [0022](0022-procedure-overload-identity-and-foreign-key-resolution.md) | SPECIFICNAME-based procedure identity and REFKEYNAME-based foreign key resolution | Accepted |
| [0023](0023-response-payload-and-cell-size-limits.md) | Bound response payload and cell size, normalize JDBC values | Accepted |
| [0024](0024-honest-ci-test-tiers-and-genuine-mcp-smoke-test.md) | Honest CI test tiers (CR-006) and a genuine end-to-end MCP smoke test (CR-007) | Accepted |
| [0025](0025-output-contract-snapshot-all-tool-observability-correlation-id.md) | Output contract snapshot, all-tool observability, and client-visible correlation IDs | Accepted |
| [0026](0026-bounded-configurable-fail-fast-audit-file-lifecycle.md) | Bounded, configurable, fail-fast audit-file lifecycle | Accepted |
| [0027](0027-phase-p3-dependency-inversion.md) | Phase P3 — dependency inversion (neutral models, outbound ports, error-classifier port) | Accepted |
| [0028](0028-phase-p4-capability-discovery-and-adapter-selection.md) | Phase P4 — capability discovery and adapter selection (getCapabilities, explicit adapter activation) | Accepted |
| [0029](0029-phase-p5-portability-evidence.md) | Phase P5 — portability evidence (fake adapter, dual-adapter contract tests) | Accepted |
| [0030](0030-local-observability-export-cr010.md) | Local, logging-based export for metrics and traces (CR-010) | Accepted |
| [0031](0031-eof-shutdown-response-drop.md) | Draining in-flight tool calls before stdin-EOF shutdown | Accepted |
| [0032](0032-advanced-query-differentiated-limits.md) | Give ADVANCED_QUERY a real, differentiated policy | Accepted |
| [0033](0033-explain-plan-acquisition.md) | Explain-plan acquisition via a dedicated ExplainPort | Accepted |
| [0034](0034-query-complexity-controls.md) | Deterministic query complexity limits (cross joins, join count, nesting depth) | Accepted |
| [0035](0035-tool-execution-admission-control.md) | Performance and red-team testing (thread 4) - tool-execution admission control, and two SQL policy gaps closed | Accepted |
| [0036](0036-dynatrace-observability-export.md) | Dynatrace-specific observability export (thread 5) | Accepted |
| [0037](0037-http-transport-and-authentication-prototype.md) | HTTP transport and authentication prototype (thread 6) | Accepted (experimental prototype boundary only) |
| [0038](0038-human-governed-cross-model-ai-engineering.md) | Adopt a human-governed, cross-model AI engineering workflow | Accepted |
| [0039](0039-close-p0-findings-2026-09-01-review.md) | Close the three P0 findings from the 2026-09-01 master-brief gap review | Accepted |
| [0040](0040-tool-input-bean-validation.md) | Bean Validation for MCP tool input contracts (#39) | Accepted |
| [0041](0041-correlation-id-and-result-bytes-observability.md) | Correlation ID propagation and a result-byte metric (#40) | Accepted |
| [0042](0042-disconnect-triggered-query-cancellation.md) | Disconnect-triggered query cancellation (#46) | Accepted |
| [0043](0043-remaining-ci-quality-gates.md) | Remaining CI quality gates - Enforcer, Spotless, SpotBugs (#42) | Accepted |
| [0044](0044-automated-live-db2-integration-test-suite.md) | Automated live-DB2 integration test suite (#44) | Accepted |
| [0045](0045-hard-universal-payload-bound.md) | A hard application-result payload bound and the BoundedList envelope (#62) | Accepted |
| [0046](0046-security-scan-at-promotion.md) | Run security static analysis at promotion, not on every push (#86) | Accepted |
| [0047](0047-public-edition-curation.md) | How the public edition is curated - snapshot first, normal promotion after | Accepted |
| [0048](0048-external-jdbc-driver.md) | Do not distribute the IBM JDBC driver; load it from outside the jar | Accepted |
| [0049](0049-no-unverifiable-third-party-licences.md) | Do not distribute a component whose licence cannot be verified | Accepted |

## Master brief topics not yet covered by an ADR

Informational only — not a numbering reservation. When one of these gets
decided, it becomes the next sequential ADR number, whatever that is at
the time:

- Use typed MCP contracts instead of generic maps (already substantially
  true in practice since Phase 1/2 — every tool returns typed records,
  never a generic map — but never formally written up as its own ADR)
- Keep the public core separate from private extensions

See `docs/implementation-plan.md` for phase-by-phase status.

## Format

Each ADR follows the standard Context / Decision / Consequences structure
(Michael Nygard style). Superseded decisions are marked as such and link
forward to the ADR that replaced them — none are deleted, so the reasoning
trail stays intact.

## Status lifecycle

`Proposed` — the decision, its drivers, and the alternatives considered
are written down before implementation starts, so the reasoning exists
independent of whether the code later works out. `Accepted` — the
decision has been implemented and verified; Consequences is updated to
reflect what was actually found, not just what was anticipated (e.g. ADR
0002's semicolon note, ADR 0005's Surefire regression note). ADRs 0001-0006
in this project were written directly as `Accepted`, after their code
already existed and was tested. ADR 0007 was the first to use the
`Proposed` stage deliberately — matching how ADRs are conventionally
written in practice (decision-time, not after-the-fact) — and 0008-0009
followed the same pattern.

## Why the numbers were reshuffled once

ADRs 0004 (credentials) and 0005 (layer separation) were originally
numbered 0013 and 0004 respectively; ADR 0008 (schema allowlist) and ADR
0009 (testing without live DB2) were originally 0014 and 0015. All four
were renumbered to close the gaps left by the abandoned brief-topic-slot
scheme described above. This is the only renumbering this project intends
to do — going forward, new ADRs simply take the next sequential number.
