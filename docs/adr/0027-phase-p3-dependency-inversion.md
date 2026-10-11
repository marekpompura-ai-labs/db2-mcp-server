# ADR 0027: Phase P3 — dependency inversion

## Status
Accepted — implemented and verified in this environment (`mvn verify`
green, 164 tests, up from 162; two new ArchUnit rules, one proven able to
fail against the pre-refactor code with 9 real violations, not just
written and assumed correct; packaged-jar smoke test re-confirms the real
MCP session still serializes tool results correctly end to end).

## Date
Proposed 2026-08-17, Accepted 2026-08-17.

## Context
Phase P3 of the adopted database-portability extension (ADR 0021), in the
extension's own words (`docs/master-brief-extension-database-portability.md`
§15, "Suggested implementation phases"):

> Phase P3 — Dependency inversion: move neutral models out of
> `mcp.contract`; introduce coherent outbound ports; map domain/application
> results in the MCP inbound adapter; move Db2 error classification behind
> a port; extend ArchUnit rules.

Two concrete, real violations existed before this phase, found by
inspecting the actual code rather than assumed from the plan:

1. `MetadataService`/`QueryService` (`application.metadata`/
   `application.query`) — the outbound ports the `mcp.tool` layer already
   depended on, correctly, per ADR 0005's dependency direction — returned
   `mcp.contract.*` types directly. The extension's own target
   architecture (§4) is explicit: "application ports must not return
   classes owned by `mcp.*`." A future non-Db2 adapter implementing these
   ports would have had to depend on MCP transport types to do so, which
   defeats the purpose of a port.
2. `Db2SqlStateClassifier`, a static utility mapping DB2 SQLSTATE codes to
   `ErrorCategory`, lived in `domain.error` - the one package every other
   layer is allowed to depend on and that is supposed to know nothing
   database-vendor-specific. The extension (§10) is explicit here too:
   "database-specific error classification must implement a common
   error-classification port."

## Decision drivers
- Prove the violations were real before fixing them, the same discipline
  every prior ADR in this project has used - not "the plan says to do
  this," but "here is the actual offending code."
- The extension's own required working method (§15) says to refactor with
  the smallest coherent change, not mechanically invent interfaces the
  codebase doesn't need. `application` already contained exactly two
  classes (`MetadataService`, `QueryService`) - no separate use-case
  orchestration layer exists between `mcp.tool` and the Db2 adapter, and
  none was invented here either.
- `QueryResult`'s `List<List<Object>>` row representation is exactly the
  kind of long-term domain contract §7.3 of the extension says not to
  rely on forever - but redesigning it is explicitly out of P3's own
  bullet list (which asks only to relocate models, not redesign them),
  and ADR 0023 already deliberately decided against a bigger envelope
  type for a related reason. Left alone here, on purpose.
- A rule that has never been observed to fail is not yet trusted in this
  project (ADR 0005's Surefire regression note, ADR 0025's output
  snapshot test, this project's whole quality-gate philosophy). Every new
  ArchUnit rule in this ADR was run against the code *before* the fix
  landed to confirm it genuinely caught the violation.

## Considered options
1. **Move the nine result records from `mcp.contract` to `domain.model`,
   keep them as the direct return type of both the outbound ports and the
   `mcp.tool` methods (identity mapping at the MCP boundary).** Chosen.
   The records are already JSON-safe, closed-shape values with no
   framework dependency - inventing a second, structurally identical
   `mcp.*`-owned copy purely to satisfy "MCP adapter may map domain models
   into transport-specific results" literally would be exactly the kind
   of unnecessary abstraction this project's own engineering principles
   reject. The "mapping" step exists structurally (in `mcp.tool`, the
   layer the extension calls the MCP inbound adapter) - it is the
   identity function today, which is a legitimate, documented choice, not
   a missing step.
2. **Rename `MetadataService`/`QueryService` to `MetadataPort`/
   `QueryPort` and move them to `application.port.out`.** Chosen. The
   extension's own illustrative naming (§7.1) uses `*Port` for exactly
   this role; "Service" implemented by an "Adapter" reads backwards for a
   hexagonal outbound port and was worth the small, purely mechanical
   rename (two interfaces, nine call sites, all compile-checked).
3. **Introduce a `DatabaseErrorClassifier` port in `domain.error`; move
   `Db2SqlStateClassifier` into `adapter.db2`, converted from a static
   utility to an injected `@Component`.** Chosen over leaving it static
   in `domain.error` - the location was the actual defect (Db2-specific
   knowledge in the vendor-neutral layer), and Spring already manages
   every other collaborator these two adapters need, so constructor
   injection is consistent with the rest of the class, not a new pattern.
4. **Redesign `QueryResult`'s row representation into the extension's
   §7.3 richer value model now.** Rejected for this phase - not one of
   P3's five bullets, would be a breaking MCP output-contract change
   requiring its own ADR and golden-snapshot review (ADR 0025), and
   nothing found while doing this refactor showed it was needed to
   satisfy P3's actual dependency-inversion goal.

## Decision
- **Domain model relocation.** All nine result records (`ColumnInfo`,
  `ProcedureDetail`, `ProcedureInfo`, `ProcedureParameterInfo`,
  `ProcedureSource`, `QueryColumn`, `QueryResult`, `ReferenceDataEntry`,
  `SchemaInfo`, `TableDependencyInfo`, `TableInfo` - eleven files, nine
  distinct "info" concepts plus two supporting records) moved from
  `mcp.contract` to `domain.model`, via `git mv` plus a package-
  declaration update; no field, no shape, no serialized JSON changed.
  `mcp.contract` no longer exists as a package.
- **Outbound ports.** `application.metadata.MetadataService` and
  `application.query.QueryService` moved to `application.port.out` as
  `MetadataPort` and `QueryPort`, now returning only `domain.model.*` and
  primitive types. `Db2MetadataAdapter`/`Db2QueryAdapter` implement these
  renamed interfaces; `SchemaTools`/`QueryTools`/`ReferenceDataTools`
  (the MCP inbound adapter, in the extension's own vocabulary) depend
  only on the port interfaces, unchanged from before this ADR.
- **Error classification behind a port.** New
  `domain.error.DatabaseErrorClassifier` interface (`ErrorCategory
  classify(String vendorErrorCode)`). `Db2SqlStateClassifier` moved to
  `adapter.db2`, converted from a static utility class to an injected
  `@Component implements DatabaseErrorClassifier`. `Db2QueryAdapter` and
  `Db2MetadataAdapter` both gained a `DatabaseErrorClassifier
  errorClassifier` constructor parameter, replacing the static
  `Db2SqlStateClassifier.classify(...)` call with
  `errorClassifier.classify(...)`.
- **Extended ArchUnit rules**, both in `LayeringArchitectureTest`:
  - `application_layer_must_not_depend_on_mcp_layer` - proven to fail
    against the pre-refactor code first (see Consequences), then made to
    pass by the port-return-type fix.
  - `database_error_classifiers_must_live_in_an_adapter_package` -
    `classes().that().implement(DatabaseErrorClassifier.class).should()
    .resideInAPackage("..adapter..")`, proven able to fail by temporarily
    pointing the assertion at a nonexistent package and confirming
    ArchUnit reports the real violation, then restored.

## Consequences
- **The `application_layer_must_not_depend_on_mcp_layer` rule was proven
  to genuinely catch something, not written to trivially pass.** Added
  before the port fix and run against the code as it stood: it failed
  with 9 reported violations, one per `MetadataPort`/`QueryPort` method,
  each naming the exact `mcp.contract.*` return type it depended on. Only
  after the relocation did the rule pass.
- **The `database_error_classifiers_must_live_in_an_adapter_package` rule
  was proven the same way**, in reverse: temporarily changed to assert
  residence in a nonexistent package, confirmed ArchUnit correctly
  reports `Db2SqlStateClassifier` violating it, then restored to the real
  assertion.
- **Zero behavior change.** Every moved record kept its exact field
  names, types, and order; every renamed interface kept its exact method
  signatures apart from the return-type package. The MCP contract
  snapshot (ADR 0013, inputs) and output contract snapshot (ADR 0025,
  outputs) are both unaffected - neither embeds a Java package or class
  name, only tool names and field-level JSON shape, confirmed by
  inspecting both golden files directly before making this claim.
  `Db2SqlStateClassifierTest`'s classification assertions are unchanged
  in substance, only in how the classifier is obtained (`new
  Db2SqlStateClassifier()` instead of a static call).
- **Verified against the real packaged jar, not just unit tests.**
  `scripts/smoke-test.sh` re-run after this refactor: `tools/list` still
  returns the correct 9 tools with unchanged input schemas, a `tools/call`
  error response still carries `errorCode`/`message`/`sqlState`/
  `correlationId` exactly as ADR 0025 established - proving the identity
  mapping at the `mcp.tool` boundary genuinely still serializes the moved
  types correctly, not just that the code compiles.
- **`QueryResult`'s row representation is deliberately untouched.** Still
  `List<List<Object>>`, still exactly what ADR 0023 shipped. The
  extension's own §7.3 richer value-model guidance remains a "designed
  but not required now" item (§3.2), not silently dropped - recorded here
  as explicitly deferred, not forgotten.
- **`docs/architecture-diagrams.md` §14.1 updated** to name
  `application.port.out` / `MetadataPort` / `QueryPort` instead of the
  old `application` / `MetadataService` / `QueryService` labels - the one
  diagram in that document that named the now-renamed layer.
  `docs/adr/0005-layer-separation.md` is deliberately left as originally
  written (it accurately recorded the decision as it stood in Phase 1) -
  ADR history isn't rewritten retroactively in this project; this ADR is
  the forward link instead, the same pattern ADR 0021 already used for
  ADR 0005 regarding Phase P3's ArchUnit extensions.
- **Full suite: 164 tests** (up from 162) - 1 new
  (`database_error_classifiers_must_live_in_an_adapter_package`);
  `application_layer_must_not_depend_on_mcp_layer` is the other net-new
  rule, and every existing test kept passing unmodified in assertion
  content, only in import paths. JaCoCo policy-package coverage gate and
  the other five ArchUnit rules unaffected.

## Verification / enforcement
`mvn verify -DskipITs` (164 tests). Both new ArchUnit rules proven able to
fail before being trusted: `application_layer_must_not_depend_on_mcp_layer`
against the real pre-refactor code (9 violations), `database_error_
classifiers_must_live_in_an_adapter_package` via a deliberately broken
assertion. Packaged jar re-verified via `scripts/smoke-test.sh`: correct
tool list, correct error-response shape, no credential leakage, clean
shutdown.

## Related decisions
ADR 0021 (adopted the extension this phase implements), ADR 0005 (layer
separation and the original ArchUnit rules this phase extends, not
replaces, left as originally written), ADR 0013 / ADR 0025 (the input and
output MCP contract snapshots, both confirmed unaffected since neither
embeds Java package names), ADR 0018 (structured error model -
`DatabaseErrorClassifier` is the new seam behind `McpToolException`'s
error-category resolution), ADR 0023 (the prior deliberate decision not
to redesign `QueryResult`'s shape, extended here to explicitly cover the
row-representation question P3 raised but did not require answering).
