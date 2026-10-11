# Project Baseline Status (pre-master-brief)

This document captures the state of the DB2 MCP Server project as it stood
from the original creation prompt, todos, and test schema — **before** the
`governed-db2-mcp-gateway-master-brief.md` reframing. It exists so the
original scope and the master brief's expanded scope can be reviewed
side by side, and refactored/reconciled deliberately rather than by
default.

> **Correction (verified by filesystem/Git audit):** an earlier version of
> this document described the nine tools and the 20-test integration suite
> as completed work, based on the checkmarks in `todos.global.githubsafe`.
> A full audit of this environment — filesystem search for `pom.xml`,
> `build.gradle`, and `*.java` anywhere on disk; `git status --ignored`;
> and `git log --all --name-only` across every branch — found **no Java
> source, no build file, and no evidence of the implementation anywhere
> reachable from this session**. The todos file's checkboxes describe
> **intended/planned scope**, not verified completed work. Treat every
> implementation claim below (tools, tests, configuration) as **planned**
> unless it is independently implemented and verified in this repository
> going forward.

## 1. Origin and positioning

The project started from three uploaded documents:
`prompt.create.db2.mcp.server.md`, `todos.global.githubsafe`, and
`prompt.create.db2.mcp.test.schema`. They describe a **read-only MCP
server for IBM DB2** — letting Claude Code query DB2 schema metadata and
run ad-hoc SELECTs directly, instead of switching to DBeaver.

Positioning at that stage was modest: a practical dev-productivity tool.
The portfolio angle ("candidate for Solution Engineer/Architect
transition") was added later in conversation, before the master brief
existed.

## 2. Tech stack (as specified)

- Java 21 (Temurin 21.0.4)
- Maven 3.9.9
- Spring Boot 3.4.5
- Spring AI 1.0.0 (`spring-ai-starter-mcp-server`)
- IBM DB2 JDBC driver `jcc 11.5.9.0` — originally specified as "reuse
  DBeaver's cached driver"; corrected in ADR 0003 once we confirmed it's
  published on Maven Central and actively maintained by IBM
- Transport: stdio only
- Scope: read-only (SELECT/WITH only)

## 3. Planned project layout

```
db2-mcp-server/
  config/.env.example, config/.env (gitignored)
  src/main/java/<groupId>/db2mcp/
    Db2McpServerApplication.java
    config/McpConfig.java
    service/SchemaService.java
    tools/SchemaTools.java
  src/main/resources/application.yml, logback-spring.xml
  src/test/java/.../SchemaServiceIT.java
  src/test/resources/logback-test.xml
  tests.output/  (gitignored)
  pom.xml, mcp-server.json, .gitignore
```

None of this Java code has ever been committed to **this** repository —
only the layout/spec exists here, via the uploaded prompt.

## 4. The 9 MCP tools specified

| Tool | Backed by |
|---|---|
| `listSchemas` | `SYSCAT.SCHEMATA` |
| `listTables(schema)` | `SYSCAT.TABLES` (T/V) |
| `describeTable(schema, table)` | `SYSCAT.COLUMNS` + `SYSCAT.KEYCOLUSE`/`TABCONST` for PK flags |
| `getTableDependencies(schema, table)` | `SYSCAT.REFERENCES`, both directions |
| `listProcedures(schema)` | `SYSCAT.PROCEDURES` |
| `describeProcedure(schema, name)` | `SYSCAT.PROCEDURES` + `SYSCAT.PROCPARMS` |
| `getProcedureSource(schema, name)` | `SYSCAT.PROCEDURES.TEXT` (SQL procedures only) |
| `lookupRefData(schema, table, codeColumn, descriptionColumn, filterColumn, filterValue)` | generic code/description lookup, identifier-validated |
| `executeQuery(sql)` | guarded — must start with SELECT/WITH |

## 5. Original security checklist

- Never commit credentials; `.env` gitignored, only `.env.example`
  committed; prefer `claude mcp add` (writes to `~/.claude.json`, not the
  repo)
- Read-only by construction: DB2 user read-only grants + Hikari
  `read-only: true` + `executeQuery` guard
- No string-built SQL from unvalidated input — bind values via `?`; when
  identifiers must be concatenated, validate against
  `^[A-Za-z0-9_#$@]+$` first
- Don't echo secrets in logs; stderr routing is for MCP-protocol reasons,
  not security, so logging discipline still applies separately
- Re-verify this checklist whenever a new `@Tool` builds SQL dynamically

This is the seed of what became ADR 0002 (three-layer read-only
enforcement). The master brief's version of the same idea is more
elaborate (structural SQL/AST validation, resource limits, adversarial
test catalogue), but the core principle already existed here.

## 6. Todos file — scope snapshot (planned, not verified complete)

The checkboxes in `todos.global.githubsafe` are read here as a **scope
list**, not a completion record. Nothing in this section has been
independently verified — no build artifact, source file, or test result
for any of it exists in this repository or anywhere else reachable from
this development environment.

**Marked as done in the todos file (unverified — treat as planned scope):**
- Core stack: Spring Boot 3.4.5, Java 21, MCP stdio (Spring AI 1.0.0),
  DB2 JDBC (jcc 11.5.9.0), read-only `executeQuery` guard
- Schema discovery: `listSchemas`, `listTables`, `describeTable`,
  `getTableDependencies`, `listProcedures` + `describeProcedure`,
  `getProcedureSource`
- Quality/reliability: stdio stdout fix, JVM keep-alive fix, banner
  suppressed, integration tests (`SchemaServiceIT`, 20 tests against real
  DB2, no mocks)
- Configuration: env-var credentials, HikariCP (read-only, 5 connections,
  10s timeout), registered via `claude mcp add --scope user`
- Portability: `prompt.create.db2.mcp.server.md` written as a
  database-independent, GitHub-safe creation guide

**Marked as not done in the todos file:**
- Index discovery, trigger discovery
- Explain Plan support, SQL analysis (missing-index detection), query
  result pagination
- Connection health check / `ping` tool
- Structured error responses (SQLSTATE/SQLCODE surfacing)
- Audit logging of `executeQuery` calls
- YAML profile support (multi-database), Docker packaging
- Architecture Decision Records — the item that started this thread
- MCP capability manifest, performance baseline, formal security review
- **Publish to GitHub — was still unchecked**

No functional implementation work is confirmed to exist. The entire
project — tools, tests, and configuration alike — starts from zero in
this repository. What genuinely does exist, independent of the todos
file, is the governance/documentation layer built in this repository
directly: the ADRs and this baseline record.

## 7. Test/demo schema (ITSM)

A self-contained, fictional IT Service Management schema (`REF_DATA`,
`SERVICE_CATALOG`, `CUSTOMER`, `TICKET`, `TICKET_COMMENT`, plus a
`STP_FIND_TICKETS` procedure) designed specifically to exercise all 9
tools with no proprietary data — explicitly built as a portfolio/interview
demo artifact. This predates the master brief, which adopts it wholesale
(§17) as the reproducible public demo.

## 8. What actually exists — verified by direct audit

This section previously stated the implementation "exists only on the
local machine," inferred from the todos file. That inference was wrong
and has been corrected. A direct audit of this development environment
was performed instead of relying on the todos/prompt documents:

- `pwd` / `git rev-parse --show-toplevel` → `/home/user/<private-repo>`,
  remote `the private development repository`.
- Filesystem-wide search (excluding `/proc`, `/sys`, `/dev`) for
  `pom.xml`, `build.gradle`, `build.gradle.kts` → **zero matches anywhere
  on disk**, not just outside the repo.
- Filesystem-wide search for `*.java` (excluding system/toolchain paths)
  → **zero application source files**; only unrelated system files
  matched (e.g. `/etc/.java`, a Bison parser skeleton).
- `git status --ignored` → clean working tree, nothing untracked,
  nothing ignored. No `.gitignore` exists, so nothing could be silently
  hidden from `git status` either.
- `git log --all --name-only` across every branch (`main` and
  `claude/repository-inventory-ksb610`) → the complete file history since
  `Initial commit` is `README.md` plus the markdown files under `docs/`.
  No Java file, build file, or resource file has ever been committed to
  this repository, on any branch.
- No sibling or parent directory, other mounted volume, or uploaded file
  in this session contains a DB2 MCP server implementation.

**Conclusion: this repository is documentation-only. Implementation has
not started.** What currently exists:

- `README.md` (title only)
- `docs/adr/0001–0004` (stdio transport, read-only 3-layer enforcement,
  driver sourcing from Maven Central [corrected from the original
  DBeaver-cache claim], credentials via env vars)
- `docs/master-brief.md` — the governance/architecture target, with
  §15.1 revised to the cherry-pick promotion workflow
- `docs/project-baseline-status.md` — this document

## 9. Direction shift to review

The original scope, as *specified* (not as completed — see §6, §8), was a
personal productivity tool with light security hygiene: nine tools, a
guard clause, env-var credentials, integration tests. The master brief
reframes that specification into a governed enterprise-integration
reference architecture — policy engine, capability profiles
(`metadata-only`/`safe-query`/`advanced-query`), OpenTelemetry/Micrometer
observability, ArchUnit-enforced layering, MCP contract-compatibility
tests, an adversarial security test catalogue, CI/SBOM, and a full
12-ADR set.

Since implementation has not started either way, this is not a question
of migrating existing code — it's a choice of which specification to
build against from a standing start. The master brief is the agreed
target direction (see `docs/implementation-plan.md` for the phased
approach); the original prompt's nine-tool set remains the functional
core within it.
