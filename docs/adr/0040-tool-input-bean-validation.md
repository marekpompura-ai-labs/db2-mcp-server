# ADR 0040: Bean Validation for MCP tool input contracts (#39)

## Status
Accepted

## Date
Proposed 2026-09-06, Accepted 2026-09-06.

## Context
P1-2 of the 2026-09-01 master-brief gap review (`docs/reviews/2026-09-01-master-brief-gap-review.md`,
independently confirmed in the response document, tracked as issue #39):
all 11 `@Tool` methods across `QueryTools`, `SchemaTools`, `ReferenceDataTools`,
and `ExplainTools` take raw scalar `String` parameters and validate none of
them structurally. `@Valid` appeared zero times anywhere in `src/main/java`.
A blank or null `schema`/`table`/`sql`/`specificName` was never rejected at
the tool boundary - it flowed all the way down to whichever adapter method
happened to run next, surfacing (if at all) as whatever that adapter's own
SQL/JDBC failure looked like, not as a stable, generic validation rejection.

Every `spring-boot-starter-validation` annotation already in this codebase
(`@NotBlank`, `@Positive`, `@NotNull`) lives on a `@ConfigurationProperties`
record - `pom.xml`'s own comment scoped the dependency to exactly that use
at the time it was added. This issue extends Bean Validation to a second,
previously untouched surface: tool input.

## Decision drivers
- P0-3/#36 (ADR 0039) already closed one class of leak in this codebase:
  a validator-failure catch block forwarding a raw exception's
  `getMessage()` straight into the client-visible `McpToolException`. Any
  new validation path introduced here had to be built with that precedent
  already in mind, not discover the same leak again from scratch.
- The MCP contract snapshot (`McpContractSnapshotTest`, brief section 11.3)
  pins every tool's exact JSON input schema. Whatever approach this issue
  took could not change any of the 11 tools' public signatures without a
  deliberate, reviewed contract change - and this issue's own scope
  (rejecting bad input, not redesigning the client-facing contract) gave no
  reason to accept that cost.
- "Verify, don't assume" (this project's standing research discipline,
  applied throughout ADR 0033/0035/0036/0037's own decompiled-jar checks)
  applied directly to the most natural-looking alternative design (below).

## Decision

### Explicit in-method validation of an internally-constructed request record, not Spring AOP method validation
The issue's own wording suggested the obvious Spring-idiomatic shape:
`@Validated` on each tool class plus Bean Validation constraints directly on
the `@ToolParam`-annotated method parameters, relying on Spring's AOP-based
method validation (`MethodValidationInterceptor`) to reject bad calls before
the method body runs. That was investigated and rejected.

The tool beans (`SchemaTools`, `QueryTools`, `ReferenceDataTools`,
`ExplainTools`) are not called through ordinary Spring-managed call sites -
`McpConfig.schemaToolCallbackProvider` passes them into
`MethodToolCallbackProvider.builder().toolObjects(...)`, which introspects
each object's `@Tool`-annotated methods via reflection and invokes them the
same way for the lifetime of the server. Adding `@Validated` to a
concrete class with no interfaces makes Spring wrap it in a CGLIB subclass
proxy. Whether `MethodToolCallbackProvider`'s reflection-based annotation
discovery still finds `@Tool` on the proxy's overridden methods - and
whether a `ConstraintViolationException` thrown from inside that proxy's
interceptor chain propagates in a shape `MethodToolCallback`'s own exception
handling expects - are exactly the kind of framework-internals questions
this project insists on verifying by decompiling the actual jar (ADR
0033/0035/0036/0037), not assuming from Spring's documented common case.
Getting either wrong risked a much worse failure mode than the bug being
fixed: tool discovery silently returning fewer tools, or every call to an
affected tool throwing an unhandled exception instead of the sanitized
`McpToolException` path.

Instead: a new `ToolInputValidator` (`mcp.tool`, `@Component`,
constructor-injects `jakarta.validation.Validator` - already autoconfigured
by `spring-boot-starter-validation`'s `ValidationAutoConfiguration`, no new
dependency needed) wraps `Validator.validate(Object)` directly. Each
`@Tool` method's existing scalar parameters are unchanged; at the top of the
method body, it constructs a new request record from them and passes it to
`validator.validate(...)`. On any constraint violation, `ToolInputValidator`
throws `McpToolException(ErrorCategory.VALIDATION_FAILED, "One or more tool
parameters failed validation", null, <fresh correlationId>)` - the same
enum value, the same generic phrasing style
(`SchemaAllowlistValidator`'s "One or more identifiers failed validation"),
and the same "never forward the raw framework message" discipline P0-3
established, applied here from the start rather than fixed after the fact.

This has no dependency on Spring AOP, proxying, or method interception of
any kind - it is a plain object construction and a library call, run
directly in code this project already controls and already tests. The
public method signature, the `@ToolParam` annotations, and therefore the
checked-in JSON input schema for all 11 tools are completely unchanged;
`McpContractSnapshotTest` passes with **zero diff**, confirmed by running it
rather than assumed.

### Request records (new `mcp.tool.request` package)
One record per validated `@Tool` method, each with `@NotBlank` on every
required identifier/SQL-text field: `ExecuteQueryRequest`,
`ExplainQueryRequest`, `ListTablesRequest`, `ListProceduresRequest`,
`DescribeTableRequest`, `GetTableDependenciesRequest`,
`DescribeProcedureRequest`, `GetProcedureSourceRequest`,
`LookupRefDataRequest`. `listSchemas()` and `getCapabilities()` take no
parameters and get no request record - there is nothing to validate.

`LookupRefDataRequest.filterColumn`/`filterValue` are deliberately left
without `@NotBlank` (or any constraint): both are already-optional,
already-exercised (`FakeAdapterMcpToolsTest`) parameters where a `null`
pair means "no filter." Adding a new constraint to a field this issue
never asked about would be scope creep beyond "reject missing/blank
required identifiers."

## Explicitly out of scope
- **Restructuring `@Tool` method signatures to accept the request record
  itself as the single method parameter.** Untested here whether Spring
  AI's JSON-schema generation for a single complex-object tool parameter
  produces the same flat, per-field schema shape the current
  multi-`@ToolParam`-parameter methods already have, or a nested-object
  schema instead - a materially different, contract-breaking client
  experience this issue's scope didn't call for and this ADR does not
  claim to have verified either way.
- **Wiring validation failures into the audit log / metrics / span
  triple** (the pattern `AdmissionControlledToolCallback`, ADR 0035, uses
  for its own tool-callback-layer rejection). `PolicyDecisionReason` has no
  existing value for a generic input-validation rejection, and adding one
  plus its audit/metric/span wiring is a larger, separable change than
  this issue asked for (a client-visible, sanitized rejection). Left as a
  natural follow-up, not folded in silently.
- **Constraining `filterColumn`/`filterValue`.** See Decision above.
- **AOP-based method validation.** See Decision above - investigated and
  rejected, not merely unconsidered.

## Consequences
- All 9 previously-unvalidated `@Tool` methods (of 11 total; 2 take no
  parameters) now reject blank/null required input with a stable
  `VALIDATION_FAILED` error before any adapter or database call, instead of
  whatever downstream failure happened to result. Confirmed against the
  real packaged jar, DB2 host deliberately unreachable so the two error
  paths are distinguishable by error code alone: `listTables` with a
  blank schema and `executeQuery` with a blank sql both return
  `VALIDATION_FAILED` immediately; a well-formed `lookupRefData` call
  passes validation and reaches (and fails against) the unreachable
  database instead, exactly as before this change.
- No change to the MCP tool contract - `McpContractSnapshotTest` passes
  with no diff; every tool's name, description, and JSON input schema are
  byte-identical to before this issue.
- `QueryTools`, `SchemaTools`, `ReferenceDataTools`, and `ExplainTools`
  each gained one new constructor parameter (`ToolInputValidator`).
  `McpContractSnapshotTest`, `McpConfigTest`, and `FakeAdapterMcpToolsTest`
  - the three existing tests that construct these classes directly -
  updated to pass a real `ToolInputValidator` backed by
  `Validation.buildDefaultValidatorFactory().getValidator()`; no
  `@SpringBootTest`-based test needed any change, since Spring
  autowires `ToolInputValidator` like any other bean.
- Test suite gained: `ToolInputValidatorTest` (the shared collaborator
  itself, including a dedicated leak-detection test mirroring P0-3/#36's
  own precedent - confirms the exception message never contains the raw
  Bean Validation constraint text), `QueryToolsTest`, `ExplainToolsTest`,
  `ReferenceDataToolsTest`, `SchemaToolsTest` (one per validated tool
  class, each asserting the port is never called on invalid input and is
  called with the original arguments on valid input).

## Verification / enforcement
`./mvnw verify -DskipITs` - full suite green, ArchUnit unchanged
(`mcp.tool.request` covered by the existing `..mcp.tool..` wildcard rules;
`ToolInputValidator` depends only on `jakarta.validation` and
`domain.error`, no adapter dependency), JaCoCo `policy.*` gate unaffected
(this issue's new code lives in `mcp.tool`/`mcp.tool.request`, not
`policy.*` - the same infrastructure-vs-policy-decision distinction ADR
0036 already applied to `adapter.observability`). MCP contract snapshot
confirmed unchanged, not assumed. `scripts/smoke-test.sh` re-run against
the packaged jar unmodified, confirming the new constructor parameters
didn't break bean wiring. Manual verification against the packaged jar
(described in Consequences above) exercised the real stdio JSON-RPC path
end to end, not just the unit tests.

## Related decisions
ADR 0018 (`ErrorCategory`/`McpToolException`, the error path every
rejection here reuses), ADR 0039 (P0-3's sanitized-validator-message
precedent this issue's error phrasing and leak-detection test both follow),
ADR 0006 (capability-profile tool registration, unaffected - validation
runs inside each tool method regardless of which profile registered it),
ADR 0033/0035/0036/0037 (the decompiled-jar verification discipline that
ruled out the AOP-based alternative here).
