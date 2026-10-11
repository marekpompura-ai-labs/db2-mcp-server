# ADR 0001: Use stdio transport, not HTTP

## Status
Accepted

## Context
The server needs to expose DB2 schema metadata and read-only query execution
as MCP tools to an AI coding agent (Claude Code) running on a developer's
own machine. Spring AI's MCP server starter supports two transport modes:

- **stdio** — the client (Claude Code) spawns the server as a local child
  process and speaks MCP's JSON-RPC protocol over its stdin/stdout.
- **HTTP/SSE** — the server runs as a standalone, long-lived service that
  clients connect to over the network.

## Decision
Use **stdio** for this stage of the project.

The intended usage is a single developer querying their own DB2 instance
from their own machine, on demand. stdio matches that shape exactly:
Claude Code launches the process when needed, credentials never cross a
network boundary, there is no server to keep running or patch, and there is
no authentication/authorization layer to build — the OS process boundary
*is* the trust boundary.

This constrains a few implementation details that would otherwise be
invisible:
- `spring.main.keep-alive: true` — the stdio transport provider parks the
  server on daemon threads; without this flag the JVM exits immediately
  after startup instead of waiting for requests.
- `spring.main.banner-mode: off` and all logging routed to stderr
  (`logback-spring.xml`) — stdout is reserved exclusively for the MCP
  JSON-RPC stream. A single stray log line on stdout corrupts the protocol
  and the client silently drops the connection. Same convention as `git`
  and `curl`: stdout is the protocol channel, stderr is diagnostics.

## Consequences
- **Single-user only.** The server cannot be shared across a team or
  deployed centrally in its current form — it is one process per developer,
  spawned locally.
- **No network exposure.** Credentials and query results never leave the
  developer's machine, which simplifies the security story considerably.
- **Any future multi-user or shared-service requirement needs a new ADR.**
  Moving to HTTP/SSE would reopen questions this ADR deliberately avoided:
  authentication, per-user authorization, transport encryption, and rate
  limiting. That should be a conscious decision when the need arises, not a
  drive-by config change.
