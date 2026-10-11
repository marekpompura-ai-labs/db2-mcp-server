# Security policy

## Supported versions

| Version | Supported |
|---|---|
| 1.0.x | Yes |
| < 1.0 | No |

This is a single-maintainer reference implementation. Fixes are made on a
best-effort basis; there is no service-level commitment.

## Reporting a vulnerability

**Please do not open a public issue for an unpatched vulnerability.**

Use GitHub's private vulnerability reporting: the **Security** tab of this
repository → **Report a vulnerability**. That channel is private to the
maintainer until a fix is published.

Helpful things to include, as far as you have them:

- the affected version or commit;
- the configuration in use — in particular the capability profile
  (`METADATA_ONLY`, `SAFE_QUERY`, `ADVANCED_QUERY`) and the configured
  schema allowlist, since those determine which surface is reachable;
- the input or sequence of tool calls that triggers the issue;
- what you expected the policy chain to do, and what it did instead;
- whether you reached the database, and with which privileges.

**Please do not include real credentials, production data, or real
customer identifiers in a report.** A minimal reproduction against the
ITSM demo fixtures in [`docs/demo/`](docs/demo/) is more useful and
safer for both of us.

You should get an acknowledgement within about a week. If you do not,
please assume it was missed rather than ignored, and send a reminder
through the same channel.

## Scope

**In scope** — anything that lets an MCP client reach data or perform an
action the configured capability profile and schema allowlist should have
prevented: a bypass of structural SQL validation, of the allowlist, or of
read-only enforcement; a resource limit that can be evaded; or sensitive
data appearing in audit logs, metrics, traces or error messages, which the
design states must never happen.

**Out of scope** — issues that depend on a configuration the project
documents as unsafe, on granting the database user write privileges
contrary to [ADR 0002](docs/adr/0002-read-only-enforcement.md), or on the
HTTP transport prototype, which is not a supported deployment surface.
The [threat model](docs/security/threat-model.md) states the trust
boundaries and non-goals; findings that contradict an explicitly
documented non-goal are still worth reporting, but will be treated as a
documentation question rather than a vulnerability.
