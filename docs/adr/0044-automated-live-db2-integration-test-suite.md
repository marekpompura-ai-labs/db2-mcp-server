# ADR 0044: Automated live-DB2 integration test suite (#44)

## Status
Accepted

## Date
Proposed 2026-09-09, Accepted 2026-09-09.

## Context
P1-7 of the 2026-09-01 master-brief gap review (issue #44) confirmed a
real gap, with mitigating context: exactly one `*IT.java` file existed
(`Db2LiveConnectivityIT`), a single self-skipping connectivity/basic-
catalog-read test - no broader automated live-DB2 suite existed. Most of
the specific claims the review worried about (procedure/overload
identity, FK-via-unique-constraint resolution, live `getCapabilities`)
had, in fact, already been manually verified against a real DB2 instance
with dated evidence on record (`docs/demo/guided-scenarios.md`'s "Live-DB2
verification checklist") - but "manually verified once, by hand, on a
specific date" is not the same claim as "an automated suite that proves
this again on demand," and the review was right to distinguish them.

The human project owner's decision, given the choice between deferring
this (documenting it as an accepted gap, matching P2-1/#45's own
resolution) or actually building it: build it. This ADR is that build.

## Decision drivers
- A real, already-running, already-provisioned local DB2 instance
  (`db2-mcp-local`, the same container every prior live-verification
  session in this project's history has used) made converting the
  guided-scenarios checklist's manual steps into genuine automated
  assertions immediately verifiable, not a paper design - each new test
  was run against real DB2 12.1.5.0 before being considered done, the
  same standard every other fix in this project's history has held to.
- `docker inspect` against that same real instance confirmed
  `Privileged: true` is required for the DB2 Community Edition image -
  GitHub Actions' declarative `services:` block does not support
  `privileged: true` (a documented platform limitation), so a raw
  `docker run` step was used instead, which has no such restriction.
- A DB2 container's first-boot database creation is genuinely slow
  (registered as a 20-minute health-check budget in the new workflow) and
  the image itself is large - too heavy to add to the existing per-push/
  PR `ci.yml` job without materially slowing down every ordinary commit.
  A separate, `workflow_dispatch`-only workflow keeps the fast path fast.
- The throwaway container's DB2 instance password does not need to be a
  real secret: it's created and destroyed entirely within one CI job run,
  never reachable beyond that runner's own localhost, and never
  persisted - a GitHub Actions repo secret would protect nothing a fixed,
  clearly-commented placeholder value doesn't already achieve, and avoids
  requiring the human project owner to set up a new secret before this
  workflow can run at all.

## Decision

### `Db2LiveCatalogIT` (new, `adapter.db2` package)
Same self-skip convention as `Db2LiveConnectivityIT`
(`@EnabledIfEnvironmentVariable(named = "DB2_HOST", matches = ".+")`).
Five real assertions against a live `Db2MetadataAdapter`/
`Db2CapabilityAdapter`, each automating one previously-manual
guided-scenarios checklist item:

- `listProcedures_returnsBothOverloadsOfStpFindTickets` /
  `describeProcedure_distinguishesOverloadsBySpecificName` /
  `getProcedureSource_returnsOnlyItsOwnOverloadsBody` - CR-003 (ADR 0022):
  proves `specificName` genuinely disambiguates the two `STP_FIND_TICKETS`
  overloads, not that both calls coincidentally return the same thing.
- `getTableDependencies_resolvesForeignKeyReferencingAUniqueConstraintNotThePrimaryKey` -
  CR-004 (ADR 0022): `SERVICE_CATALOG_NOTE.SERVICE_NAME` references
  `SERVICE_CATALOG`'s unique constraint, not its primary key - the one
  case in the ITSM schema built specifically to catch a
  primary-key-only assumption in `REFKEYNAME` resolution.
- `capabilities_reportsARealLiveDatabaseProductVersion_notTheDegradationFallback` -
  the first proof this server is actually talking to DB2 and not
  silently degrading (ADR 0028); asserts the real driver-reported
  `SQLvvrrmm`-style prefix, not an exact version string, so the test
  doesn't need updating on every DB2 patch release.

All five run clean on the first attempt against the real instance (5/5,
0 failures, ~4s).

### `.github/workflows/live-db2-it.yml` (new)
`workflow_dispatch` only. Starts a throwaway DB2 Community Edition
container (`--privileged`, required), waits up to 20 minutes for it to
report healthy, provisions `docs/demo/itsm-schema.sql`/
`itsm-test-data.sql` through the container's own CLP (mirroring
`docs/demo/live-db2-test-prerequisites.md`'s existing manual runbook
exactly, not a divergent CI-only procedure), runs
`./mvnw failsafe:integration-test failsafe:verify` with `DB2_HOST`
pointed at it, then tears the container down unconditionally (`if:
always()`).

## Explicitly out of scope
- **Running on every push/PR.** See Decision drivers - deliberately
  `workflow_dispatch` only, given the image size and first-boot database
  creation time.
- **A scheduled (cron) trigger.** Not added in this pass - a
  straightforward follow-up (`on: schedule`) if continuous freshness
  becomes more valuable than manual invocation, not attempted here
  without a stated need for it.
- **`explainQuery`'s live checklist items, the concurrent-response-drop
  regression (issue #29), or the Dynatrace export checklist.** Each is
  already covered by its own dedicated verification path
  (`Db2QueryAdapterExplainQueryTest`'s live-DB2 evidence recorded in ADR
  0033, `eof-shutdown-race-test.sh`, the Dynatrace-specific manual
  verification in ADR 0036) - `Db2LiveCatalogIT` targets specifically the
  metadata-catalog claims (CR-003/CR-004/`getCapabilities`) that had no
  automated coverage at all before this change, not a re-implementation
  of coverage that already exists elsewhere in a different form.
- **A DB2 service reachable from the main `ci.yml` job.** Considered and
  rejected - see Decision drivers on `services:` block incompatibility
  and per-commit cost.

## Consequences
- The specific claims P1-7 worried about (CR-003, CR-004, live
  `getCapabilities`) now have genuine automated coverage, runnable on
  demand via `workflow_dispatch` or locally against any reachable DB2
  instance (including this project's own established local Docker
  setup) - not just a dated, one-time manual record.
- `docs/demo/live-db2-test-prerequisites.md`'s existing manual runbook
  remains accurate and useful on its own (a human running the guided-
  scenarios checklist by hand still follows it unchanged) - the new
  workflow automates the same provisioning steps, it doesn't replace or
  fork the documented procedure.
- No change to the main `ci.yml` job's runtime or behavior - the new
  workflow is fully additive and separately triggered.
- `Db2LiveCatalogIT` self-skips (not fails) in every context without
  `DB2_HOST` set - ordinary local `mvn verify`, the main CI job, and any
  future contributor's machine without a local DB2 instance all continue
  exactly as before.

## Verification / enforcement
`Db2LiveCatalogIT` run directly against the real `db2-mcp-local`
instance (`failsafe:integration-test failsafe:verify` with real
`DB2_HOST`/`DB2_PORT`/`DB2_DATABASE`/`DB2_USER`/`DB2_PASSWORD`): 5 tests,
0 failures, 0 errors, ~4s. `./mvnw verify -DskipITs` (the ordinary path,
no `DB2_HOST` set) confirmed unaffected - the new IT class self-skips
exactly like `Db2LiveConnectivityIT` already does.

`.github/workflows/live-db2-it.yml` itself: triggered manually via
`workflow_dispatch` after this ADR's own code landed, confirming the
whole pipeline (container start → health-check wait → schema/data
provisioning → `failsafe` run against the real container → teardown)
actually works in GitHub's own runner environment, not just locally -
see this issue's own comment thread for the run link and result.

## Related decisions
ADR 0009 (Tier 1/Tier 2 verification split - this ADR closes a real gap
in Tier 2's own automation, not the classification itself), ADR 0022
(CR-003/CR-004, the specific catalog-behavior claims this suite
automates), ADR 0024 (honest CI test tiers - `Db2LiveConnectivityIT`'s
own self-skip convention, reused here unchanged), ADR 0028
(`getCapabilities`/`productVersion`, the third claim this suite
automates). Issue #45 (P2-1, column-level policy) is this same review's
one finding actually descoped rather than built - recorded directly in
`docs/security/threat-model.md` and the review response doc, not its
own ADR - contrasted here as the road not taken for P1-7 specifically:
the human project owner chose to build this one, not defer it.
