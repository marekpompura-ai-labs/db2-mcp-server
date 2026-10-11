# ADR 0006: Expose capabilities through explicit security profiles

## Status
Accepted

## Context
`executeQuery` lets the connected AI agent run arbitrary (guarded) SQL.
Even with the read-only enforcement from ADR 0002, this is a materially
different risk than the structured metadata-discovery tools — an LLM
choosing what SQL to run is a different trust boundary than an LLM
choosing which schema name to pass to `listTables`. The master brief
(§6) calls for this to be an explicit, opt-in capability rather than
always-on.

## Decision
Introduce a `CapabilityProfile` enum with three values:

- `METADATA_ONLY` — default. `executeQuery` is not registered as an MCP
  tool at all; the connected client cannot see or call it.
- `SAFE_QUERY` — `executeQuery` is registered and reachable, subject to
  the guard in ADR 0002 (SELECT/WITH prefix, no semicolons). Resource
  limits (timeout, row/byte caps) are still pending — ADR 0007.
- `ADVANCED_QUERY` — reserved for private-lab experimentation; not
  currently different in behavior from `SAFE_QUERY` since no
  advanced-only capability exists yet. Exists now so future
  advanced-only tools have a profile to gate on, per the brief's
  public/private separation strategy.

Configured via `db2mcp.capability-profile` in `application.yml`
(`CapabilityProperties`, bound with `@ConfigurationPropertiesScan`).

Enforcement happens at **two points**, deliberately redundant, following
the same defense-in-depth reasoning as ADR 0002:

1. `McpConfig` only adds `QueryTools` to the `ToolCallbackProvider` when
   `capabilityProfile().allowsQueryExecution()` is true — under
   `METADATA_ONLY` the tool is invisible to the MCP client, not just
   rejected when called.
2. `Db2QueryAdapter.executeQuery()` checks the profile itself and throws
   before the SQL guard even runs — so a future code path that calls the
   service directly, bypassing tool registration, is still blocked.

## Consequences
- The public default (`METADATA_ONLY`) matches the master brief's
  recommended public default exactly, with no additional configuration
  needed to get there — it's what happens if `db2mcp.capability-profile`
  is never set.
- `SAFE_QUERY` still lacks resource limits (timeout, row/payload caps) -
  the profile gates *whether* `executeQuery` exists, not yet *how
  bounded* it is once enabled. That's ADR 0007, not yet written.
- `ADVANCED_QUERY` is currently identical in behavior to `SAFE_QUERY`.
  This is intentional, not an oversight — it exists as a slot for future
  private-only capabilities, not as a currently-meaningful distinction.
