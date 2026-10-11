# ADR 0013: Guard the MCP tool contract with a golden snapshot test

## Status
Accepted — implemented and verified in this environment (`mvn test`
green, 95 tests total; the new test's ability to actually fail was
proven, not assumed).

## Date
Proposed 2026-08-13, Accepted 2026-08-13.

## Context
Master brief §11.3 calls for protecting the MCP tool contract — tool
names, descriptions, and JSON input schemas — with automated
compatibility tests, distinct from ADR 0005's ArchUnit rules (which
guard internal dependency direction, not the external contract a client
actually sees). Every tool's contract is currently generated implicitly
by Spring AI's `@Tool`/`@ToolParam` annotation processing
(`MethodToolCallbackProvider`) — nothing pins what that generation
actually produces. A rename of a `@ToolParam`, a tweaked description
string, or an upgrade of the Spring AI schema generator could silently
change what an MCP client sees as the tool's shape, with nothing in the
build calling it out.

## Decision drivers
- Must catch **any** unintentional change to name, description, or input
  schema — not just gross breakage like a missing tool.
- Must not require a live MCP client or stdio handshake to run, since
  neither is available in this environment (same constraint already
  driving ADR 0009 and ADR 0012's verification approach).
- Must cover all 9 tools, not just whichever ones the active capability
  profile currently exposes — a tool hidden under `METADATA_ONLY` still
  has a contract worth pinning for when `SAFE_QUERY`/`ADVANCED_QUERY` is
  active (ADR 0006).
- The diff on an intentional contract change should be small and
  reviewable, not a wall of reformatted JSON noise from key reordering.

## Considered options
1. **A checked-in golden JSON snapshot, compared byte-for-byte against a
   canonicalized rendering of each tool's real `ToolDefinition`.**
   Constructs the same `MethodToolCallbackProvider` `McpConfig` builds,
   but with both `SchemaTools` and `QueryTools` always included
   (mocked ports — the contract doesn't depend on port behavior). Each
   `ToolDefinition`'s name, description, and parsed `inputSchema` are
   sorted (tools by name; JSON object keys recursively, via Jackson's
   `ORDER_MAP_ENTRIES_BY_KEYS`) before serializing, so the file is stable
   across runs regardless of registration or schema-generator key order,
   and an intentional change produces a minimal diff.
2. **Assert individual field values in a normal unit test** (e.g.
   `assertEquals("listSchemas", def.name())` repeated per tool). Rejected:
   doesn't scale to 9 tools with multi-property schemas without becoming
   a wall of brittle assertions, and a new tool being added silently
   isn't caught unless someone remembers to add its assertions too — a
   snapshot diff surfaces that automatically.
3. **Rely on ArchUnit or a custom static-analysis rule instead of a
   runtime snapshot.** Rejected: the contract is a *runtime* artifact of
   annotation processing (the actual JSON schema Spring AI generates),
   not a static structural property of the code the way ADR 0005's
   layering rules are — static analysis can't see it.

## Decision
Add `McpContractSnapshotTest`
(`src/test/java/.../mcp/McpContractSnapshotTest.java`), comparing a
canonicalized, sorted JSON rendering of every tool's `ToolDefinition`
against a checked-in golden file
(`src/test/resources/mcp-contract-snapshot.json`). On first run with no
golden file present, the test writes one and fails, forcing a human to
review and commit it rather than a silent auto-accept.

## Consequences
- **Positive, and genuinely exercised:** the test was proven able to
  fail, not just assumed to work — a tool description was deliberately
  edited (`listSchemas`: "List all non-system schemas in the DB2
  database" → "List all schemas"), the test failed with a clear
  `AssertionFailedError` showing exactly that field's diff, then the
  change was reverted and the test re-confirmed green. Same "a test that
  has never been seen to fail proves nothing" discipline as ADR 0005's
  ArchUnit rule and ADR 0012's span-tree tests.
- **All 9 tools pinned**, including `executeQuery`, which is invisible
  under the default `METADATA_ONLY` profile — achieved by instantiating
  `SchemaTools`/`QueryTools` directly with mocked ports in the test,
  bypassing `McpConfig`'s capability-profile gate rather than depending
  on which profile happens to be active when the test runs.
- **Full suite unaffected:** 95 tests total (up from 94), all green.
  Packaged-jar smoke test re-run separately and unaffected by this
  change (this is a pure unit test, no Spring context).
- **Known limitation, stated rather than implied away:** this pins the
  contract as Spring AI's `MethodToolCallbackProvider` currently
  generates it — it does not verify that a real MCP client parses or
  renders that JSON schema correctly, since no live stdio handshake is
  exercised here (same class of gap as ADR 0009's Tier 2 and ADR 0012's
  live-export gap).
- **Maintenance cost, accepted deliberately:** any intentional tool
  change now requires deleting and regenerating the golden file as an
  explicit step, documented directly in the test's own failure message
  so it's discoverable without reading this ADR.

## Verification / enforcement
`mvn test` runs `McpContractSnapshotTest` as part of the standard suite;
an unintentional contract change fails the build. The test's own
`AssertionFailedError` message tells the developer exactly how to
regenerate the snapshot when a change is intentional.

## Related decisions
ADR 0005 (ArchUnit — internal dependency direction, a different kind of
contract than this one), ADR 0006 (capability profiles — why all 9 tools
must be covered regardless of active profile), ADR 0009 (verification
strategy without live infrastructure — same environment constraint).
