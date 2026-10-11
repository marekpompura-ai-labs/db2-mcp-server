# ADR 0004: Pass credentials via environment variables, not a vault

## Status
Accepted

## Context
The server needs DB2 connection credentials (host, port, database, user,
password) at startup. Options considered:

- A secrets manager / vault (e.g. HashiCorp Vault, a cloud provider's
  secrets service), fetched at runtime.
- Environment variables supplied at process launch.
- Credentials hardcoded into a config file committed to the repo.

The last option was rejected outright — committed credentials are a
non-starter regardless of repo visibility.

## Decision
Use **environment variables**, injected via `claude mcp add --scope user -e
KEY=value ...`. Claude Code stores this configuration in `~/.claude.json`,
which lives outside the project repository entirely. Only a placeholder
template (`config/.env.example`) is committed; the real `config/.env` is
gitignored and never leaves the developer's machine.

A full secrets-manager integration was considered and deferred: it adds
infrastructure (a running vault, network access to it, auth to *that*
service) that isn't justified for a single-developer local tool where the
credential's blast radius is already scoped to one machine and one stdio
process.

## Consequences
- **Appropriate for the current scope** (individual local use) — the
  credential never touches the repo, and the trust boundary is the
  developer's own machine, which env vars don't weaken further.
- **Not sufficient for team or production deployment.** If this server is
  ever run centrally, shared across developers, or deployed to a server
  that multiple people or CI pipelines can reach, environment variables
  stop being adequate and a vault-backed secret injection becomes the
  correct next decision — that transition should get its own ADR rather
  than being backfilled quietly.
- **Secrets can still leak through logs if this discipline isn't
  maintained.** The logback config routes everything to stderr for MCP
  protocol reasons (see ADR 0001), not security ones — connection strings
  and passwords must still never be logged at any level, independent of
  which stream they'd land on.
