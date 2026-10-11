# Governed Db2 MCP Gateway — Code Review Report

**Review date:** 2026-08-14  
**Reviewed artifact:** `db2-mcp-server-review.zip`  
**Review type:** Static architecture, security, correctness, test, operability, and portability review  
**Target state:** Corporate-independent portfolio implementation suitable for live IBM Db2 validation and later curated public release

## 1. Executive summary

The project is a strong portfolio-grade foundation. It demonstrates substantially more than a thin MCP wrapper around JDBC: it contains explicit trust boundaries, capability profiles, structural SQL validation, schema scoping, bounded query execution, structured errors, audit concepts, metrics and tracing instrumentation, architecture tests, contract snapshots, ADRs, a threat model, and an honest H2-versus-live-Db2 verification model.

The codebase is not yet ready for a live Db2 rollout or public `0.1.0` release. Two security findings are release blockers:

1. `lookupRefData` reads arbitrary row data while remaining exposed under `METADATA_ONLY`, and it bypasses the schema allowlist and query resource controls.
2. Metadata tools do not share the sanitized error boundary implemented by `executeQuery`; raw database exceptions may reach the MCP client.

There are also important live-Db2 correctness gaps in stored-procedure overload handling and foreign-key resolution, plus missing response-payload limits and JDBC value normalization. CI terminology and behavior do not fully agree: `*IntegrationTest` is not a default Maven Failsafe pattern, and the packaged-jar smoke test does not currently perform an MCP JSON-RPC exchange.

Database portability is promising but incomplete. Db2 catalog SQL is localized in `adapter.db2`, which is a good starting point, but application ports currently depend on MCP-layer DTOs, raw SQL remains dialect-specific, tool descriptions expose Db2 concepts, and there is no adapter-selection or capability-discovery abstraction. The recommended target is **adapter-ready architecture with Db2 as the only implemented database**, not immediate Oracle or PostgreSQL implementations.

## 2. Review scope and limitations

Reviewed areas:

- Maven configuration and CI workflow;
- MCP tool registration and contracts;
- application ports and package dependencies;
- Db2 metadata and query adapters;
- SQL validation, capability profiles, and schema allowlisting;
- error handling, audit, metrics, and tracing;
- H2 integration tests, architecture tests, and contract snapshots;
- documentation, security claims, and corporate-independence indicators;
- architectural portability to Oracle and PostgreSQL.

The review environment did not provide a `mvn` executable, and the archive did not contain Maven Wrapper files. Tests and packaging therefore could not be executed during this review. Findings are based on static inspection. A green external CI build would not invalidate the design and policy findings below.

No obvious employer names, corporate project identifiers, real credentials, internal hostnames, or proprietary schema names were found in the supplied working tree. Git history was not included and was not reviewed.

## 3. Severity model

| Priority | Meaning |
|---|---|
| P0 | Release blocker; security boundary or material data-protection failure |
| P1 | Must be resolved before live Db2 validation/public release |
| P2 | Important quality, operability, or portability improvement |
| P3 | Documentation or maintainability improvement |

## 4. Findings

### CR-001 — `lookupRefData` bypasses the intended data-access policy

**Priority:** P0  
**Area:** Security / authorization / resource governance  
**Affected code:** `Db2MetadataAdapter.lookupRefData`, `SchemaTools.lookupRefData`, `McpConfig`

`lookupRefData` is registered together with metadata tools and remains available under `METADATA_ONLY`. It does not read metadata; it returns actual row values selected from caller-supplied schema, table, and column identifiers.

Current controls are limited to an identifier-character allowlist. The call does not enforce:

- capability authorization appropriate for row-data access;
- `db2mcp.allowed-schemas`;
- allowed tables or allowed column pairs;
- maximum rows;
- statement timeout through the bounded query executor;
- maximum cell or response payload size;
- audit, metrics, or tracing.

An MCP client can therefore use repeated calls to extract two columns at a time from any table readable by the configured database principal, including while the advertised capability profile is `METADATA_ONLY`.

**Required remediation:**

1. Classify `lookupRefData` as a row-data capability, not metadata.
2. Hide it under `METADATA_ONLY`; expose it only in an explicitly authorized profile.
3. Apply the same schema-scope enforcement used by guarded queries.
4. Prefer configured reference-data datasets over arbitrary caller-selected tables and columns, for example:

   ```yaml
   db2mcp:
     reference-data:
       datasets:
         ticket-status:
           schema: ITSM
           table: REF_DATA
           code-column: CODE
           description-column: DESCRIPTION
           discriminator-column: CATEGORY
   ```

5. Apply row, timeout, cell-size, and payload-size limits.
6. Include the call in the common audit/metrics/tracing path.

**Acceptance evidence:** adversarial tests must demonstrate that `METADATA_ONLY` does not expose row-data tools and that disallowed schemas/tables/columns cannot be accessed through `lookupRefData`.

### CR-002 — Metadata tools have no shared sanitized error boundary

**Priority:** P0  
**Area:** Security / information disclosure  
**Affected code:** `Db2MetadataAdapter`, `SchemaTools`, `McpToolException`

`Db2QueryAdapter` converts `DataAccessException` into a sanitized `McpToolException`. `Db2MetadataAdapter` does not. Driver or Spring JDBC exceptions from the seven catalog-backed tools and `lookupRefData` can propagate through the tool handler.

Depending on the driver error, the client-visible message may disclose object names, SQL fragments, driver details, connection information, or internal topology. This contradicts the threat model's untrusted-client assumption and the README's sanitized-error claim.

**Required remediation:** introduce one common invocation boundary for every MCP tool:

```text
MCP invocation
  -> correlation context
  -> capability and scope policy
  -> metrics/span/audit start
  -> application port
  -> exception classification and sanitization
  -> structured MCP result/error
```

Avoid duplicating independent `try/catch` blocks across tool methods. Database-specific SQLSTATE classification may remain in the outbound database adapter, but raw exceptions must not cross the inbound MCP boundary.

**Acceptance evidence:** end-to-end MCP tests for every tool category must induce a database error and verify that raw SQL, identifiers not already supplied by the caller, driver classes, hostnames, usernames, and stack traces are absent.

### CR-003 — Stored-procedure overloads are not uniquely identified

**Priority:** P1  
**Area:** Db2 correctness / MCP contract  
**Affected code:** procedure queries and DTOs in `Db2MetadataAdapter`

Procedures are selected using only `PROCSCHEMA` and `PROCNAME`. Db2 supports multiple specific routines with the same schema/name. `SYSCAT.PROCEDURES` and `SYSCAT.PROCPARMS` expose `SPECIFICNAME` for this purpose.

Consequences:

- `describeProcedure` selects the first returned overload;
- parameter rows from multiple overloads may be combined;
- `getProcedureSource` may return an arbitrary overload;
- the MCP client cannot select the intended routine unambiguously.

**Required remediation:** add `specificName` to procedure discovery and subsequent tool inputs. Queries for parameters and source must use schema plus `SPECIFICNAME`. Define the contract migration deliberately because it changes input/output shapes.

**Live-Db2 test:** create at least two procedures with the same schema/name and different signatures; verify independent discovery, description, and source retrieval.

### CR-004 — Foreign-key resolution assumes that the parent key is a primary key

**Priority:** P1  
**Area:** Db2 correctness  
**Affected code:** `TABLE_DEPENDENCIES_SQL`

The query locates the parent key through `SYSCAT.TABCONST.TYPE = 'P'`. A foreign key can reference a unique parent key. `SYSCAT.REFERENCES.REFKEYNAME` identifies the actual parent key and should be used to join `SYSCAT.KEYCOLUSE`.

The current implementation can return incorrect columns or no relationship for foreign keys referencing unique constraints.

**Required remediation:** join parent key columns using `r.REFKEYNAME`, `r.REFTABSCHEMA`, `r.REFTABNAME`, and matching `COLSEQ`.

**Live-Db2 tests:** cover primary key, unique key, composite key, inbound relationship, and outbound relationship.

### CR-005 — Row limits do not bound response payload or normalize JDBC values

**Priority:** P1  
**Area:** Availability / serialization / data governance  
**Affected code:** `Db2QueryAdapter.executeQuery`, `QueryResult`

`maxRows + 1` correctly detects row truncation, but one row can contain a very large CLOB, BLOB, XML value, binary value, or driver-specific object. `ResultSet.getObject()` is copied directly into `List<Object>`.

Risks:

- excessive heap use and MCP payload size;
- LLM context exhaustion;
- non-deterministic JSON serialization failures;
- accidental transmission of large binary or sensitive values;
- no indication that an individual cell was truncated.

**Required remediation:**

- add `maxPayloadBytes` and `maxCellBytes`;
- normalize JDBC values to an explicit JSON-safe value model;
- define handling for `Clob`, `Blob`, `SQLXML`, `byte[]`, temporal types, decimals, and unknown driver types;
- expose result-level and cell-level truncation metadata;
- stop materializing additional cells once the payload budget is exhausted.

### CR-006 — Failsafe configuration and test naming do not agree

**Priority:** P1  
**Area:** Build / verification  
**Affected code:** `pom.xml`, `.github/workflows/ci.yml`, test class names

The POM says `*IntegrationTest.java` classes run under Maven Failsafe and that `-DskipITs` skips them. Default Failsafe includes are `IT*.java`, `*IT.java`, and `*ITCase.java`; `*IntegrationTest.java` normally matches Surefire's `*Test.java` convention instead.

As configured, Failsafe may execute no tests while H2 integration tests execute during the unit-test phase. There are currently no supplied live-Db2 integration tests.

**Required remediation:** define explicit test tiers:

- unit and H2 component/integration tests: Surefire;
- live Db2 tests: Failsafe with `*IT.java` or explicit includes and a dedicated Maven profile;
- CI without Db2: run unit/H2 tests and skip only the live-Db2 profile;
- Db2 validation pipeline: run the live profile against an authorized test instance.

Add Maven Wrapper files so the documented build is reproducible without a preinstalled Maven binary.

### CR-007 — The packaged-jar smoke test is not an end-to-end MCP test

**Priority:** P1  
**Area:** MCP integration / CI claims  
**Affected code:** `scripts/smoke-test.sh`

The script verifies startup, tool-count logging, stdout cleanliness, configuration reporting, connectivity-check logging, credential non-disclosure, and SIGTERM shutdown. It does not send MCP JSON-RPC messages.

The documentation and comments describing it as an end-to-end MCP flow are therefore stronger than the test evidence.

**Required remediation:** add a real stdio exchange covering:

1. `initialize`;
2. `notifications/initialized`;
3. `tools/list`;
4. at least one `tools/call` success or controlled error;
5. response parsing and structured-error assertions;
6. stdin closure and EOF-triggered shutdown.

### CR-008 — Contract snapshot protects inputs but not output contracts

**Priority:** P2  
**Area:** Compatibility  
**Affected code:** `McpContractSnapshotTest`, MCP result records

The golden snapshot covers tool name, description, and input schema. Changes to `QueryResult`, `ColumnInfo`, `ProcedureInfo`, or other result records are not detected as contract changes.

**Required remediation:** add golden serialized result fixtures or explicit output-schema snapshots. Treat the procedure-overload fix as a deliberate contract version change.

### CR-009 — Observability is implemented only for `executeQuery`

**Priority:** P1  
**Area:** Auditability / observability  
**Affected code:** `Db2QueryAdapter`, `SchemaTools`, observability adapters

Correlation IDs, audit events, metrics, and spans are created only inside `Db2QueryAdapter`. The remaining tools have no equivalent visibility. This does not yet support the README's end-to-end observability statement.

**Required remediation:** instrument the common MCP invocation boundary, not an individual database adapter. Record at minimum tool, outcome, duration, correlation/trace linkage, policy decision, bounded result counts, and sanitized database outcome for every tool.

### CR-010 — Telemetry is instrumented but no usable export path is demonstrated

**Priority:** P2  
**Area:** Operability / portfolio evidence

Metrics are written to a `MeterRegistry`, but the stdio-only application exposes no metrics endpoint and configures no external registry. Tracing dependencies and span creation exist, but the supplied configuration does not clearly prove that the logging exporter emits the created spans.

**Required remediation:** provide one demonstrable local observability profile, for example:

- localhost-only management endpoint with Prometheus metrics; and
- OTLP export to a local collector/Jaeger, or an explicitly configured logging exporter bean.

Until verified, documentation should distinguish **instrumented** from **exported and observable**.

### CR-011 — Audit-file lifecycle is not production-safe

**Priority:** P2  
**Area:** Operability  
**Affected code:** `logback-spring.xml`

The audit logger writes to `logs/audit.log` using a non-rolling `FileAppender`. There is no size/time rotation, retention policy, failure strategy, or documented filesystem-permission requirement.

**Required remediation:** use a rolling policy with bounded retention, make the path configurable, document permissions and ownership, and define behavior if the audit destination cannot be opened.

### CR-012 — Correlation IDs are internal and cannot be tied back to a client-visible result

**Priority:** P2  
**Area:** Supportability / incident investigation

`executeQuery` generates a UUID for audit but does not return it in successful results or structured errors. A caller reporting a failed tool call cannot provide the server-side correlation ID.

**Required remediation:** define a common result/error envelope containing a safe `correlationId`, or propagate an MCP-request correlation identifier consistently into audit and trace context.

## 5. Positive engineering assessment

The following design decisions are strong and should be preserved:

- explicit assumption that the LLM is untrusted;
- fail-closed `METADATA_ONLY` default for ad-hoc query execution;
- omission of disabled tools from discovery;
- AST-based statement validation layered with a cheap first-pass guard;
- schema-qualified table enforcement;
- dedicated bounded `JdbcTemplate` for ad-hoc queries;
- `maxRows + 1` truncation detection;
- SQL fingerprinting instead of raw-SQL audit logging;
- structured MCP errors for guarded queries;
- architecture tests and contract snapshots;
- elevated coverage policy for security-sensitive code;
- ADR-based decision history;
- explicit H2 versus live-Db2 verification tiers;
- fictional ITSM demo content;
- documented public/private repository separation and clean-history requirement.

## 6. Database-portability assessment

**Current rating:** approximately **5/10 — promising separation, not yet adapter-ready**.

### Already favorable

- Db2 catalog SQL is localized in `adapter.db2`.
- MCP tools call application interfaces instead of JDBC directly.
- Most DTOs contain generally understandable database concepts.
- Query execution relies on standard JDBC abstractions.
- SQL policy is in a separate package.
- ArchUnit protects several dependency boundaries.

### Current coupling

- application interfaces import DTOs from `mcp.contract`;
- outbound adapters construct MCP-facing DTOs directly;
- tool descriptions explicitly mention Db2 and `SYSCAT`;
- datasource configuration, application identity, errors, and SQLSTATE classification are Db2-specific;
- `executeQuery(String sql)` makes the LLM responsible for the selected database dialect;
- there is no database capability/dialect descriptor;
- no conditional adapter selection exists;
- adding another implementation of the same application ports would cause Spring bean ambiguity;
- procedures, metadata catalogs, dependencies, identifier rules, explain plans, and data types differ materially across Db2, Oracle, and PostgreSQL.

The detailed target and acceptance criteria are defined in the companion document `db2-mcp-server-master-brief-extension-database-portability.md`.

## 7. Recommended remediation order

1. Resolve CR-001 (`lookupRefData` policy bypass).
2. Resolve CR-002 through a common sanitized invocation boundary.
3. Resolve CR-003 and CR-004, then validate the catalog SQL against live Db2.
4. Resolve CR-005 before exposing the server to realistic LLM-driven queries.
5. Correct test-tier configuration and add Maven Wrapper (CR-006).
6. Implement a true MCP JSON-RPC smoke test (CR-007).
7. Extend contract protection and all-tool observability (CR-008, CR-009, CR-012).
8. Demonstrate a working telemetry export profile and bounded audit lifecycle (CR-010, CR-011).
9. Apply the minimum portability refactor from the companion brief extension.
10. Freeze the revised MCP contract, complete live-Db2 evidence, and only then use it as the stable integration boundary for the planned AI incident-analysis client.

## 8. Release gates

### Gate A — safe for live Db2 test environment

- CR-001 and CR-002 closed;
- database principal verified as read-only through real grants;
- payload and timeout limits active;
- no raw database errors cross the MCP boundary;
- audit path operational and bounded;
- live-Db2 test dataset contains no corporate information.

### Gate B — ready for curated public repository

- all P0 and P1 findings closed or explicitly accepted with documented rationale;
- Maven Wrapper and reproducible build available;
- real MCP smoke test passes;
- live-Db2 catalog behavior verified and documented;
- output contracts versioned/snapshotted;
- portability architecture implemented to the minimum scope defined in the companion extension;
- public repository created with clean history;
- final secret/provenance/license scan completed.

### Gate C — stable dependency for the planned incident-analysis client

- MCP tool contracts frozen for the first Copilot integration version;
- capability manifest available to the agent;
- structured success/error envelopes provide correlation IDs;
- all row-data tools share policy, payload, audit, and observability controls;
- agent tests can replace the real server with a contract-compatible fake MCP server.

## 9. Final assessment

This is a credible and unusually well-documented enterprise Java/MCP portfolio project. Its strongest quality is not the number of tools but the explicit attempt to make LLM database access governed, testable, and explainable. The remaining work is concentrated in a manageable set of boundary issues rather than requiring a rewrite.

The immediate goal should not be additional features. It should be closing the two security gaps, proving the Db2 catalog behavior against a real instance, bounding all returned data, making the MCP test genuinely end-to-end, and introducing only enough architectural abstraction to make future database adapters possible without diluting the project's Db2 identity.
