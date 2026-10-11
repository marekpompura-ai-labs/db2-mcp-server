# Master Brief Extension — Database Portability and Adapter-Ready Architecture

**Project:** Governed Db2 MCP Server  
**Extension status:** Proposed  
**Date:** 2026-08-14  
**Purpose:** Add explicit database-portability requirements to the existing master brief following the independent code review

## 1. Why this extension exists

The original project correctly focuses on IBM Db2. A future AI client, including a planned AI incident-analysis client, should nevertheless depend on stable MCP capabilities rather than on the internal implementation details of one database product.

This extension does **not** change the current product into a universal multi-database server. It adds architectural seams so that a future Oracle or PostgreSQL adapter can be implemented without rewriting the MCP transport, governance model, common tool orchestration, result envelopes, or the client's own investigation core.

The reference implementation remains Db2-first and Db2-branded. Db2-specific capabilities are allowed and encouraged where they provide real value. Portability must not reduce the server to the lowest common denominator or create abstractions unsupported by tests.

## 2. Governing principle

> Implement Db2 completely, isolate database-specific behavior explicitly, and prove replaceability through contracts and a test adapter—not through premature Oracle or PostgreSQL implementations.

Portability means:

- the MCP client can discover what the connected server supports;
- common database capabilities have stable, database-neutral semantics;
- database-specific behavior remains behind outbound adapter ports;
- adding a new database adapter does not require rewriting common MCP governance and observability;
- unsupported capabilities fail explicitly and predictably;
- the codebase does not falsely claim support for a database that has not been integration-tested.

Portability does not mean:

- all SQL dialects are interchangeable;
- every database exposes the same metadata or procedural model;
- one generic query can run unchanged on Db2, Oracle, and PostgreSQL;
- Oracle or PostgreSQL support must be implemented in the current release;
- Db2 terminology must be removed from Db2-specific adapters or documentation.

## 3. Required scope for the current project

### 3.1 Must be implemented now

1. Move database-neutral result models out of the MCP transport package.
2. Define database-neutral application ports for common capabilities.
3. Introduce a database product/dialect descriptor.
4. Introduce a capability manifest exposed to MCP clients.
5. Make the Db2 adapter explicitly selectable through configuration.
6. Separate common error categories from Db2 SQLSTATE classification.
7. Apply common policy, result bounding, structured errors, audit, metrics, and tracing outside the Db2 adapter.
8. Add contract tests using a fake/test database adapter.
9. Preserve all Db2 functionality and live-Db2 verification requirements.

### 3.2 Must be designed but need not be fully implemented now

- optional capability namespaces for indexes, triggers, routines, explain plans, and vendor-specific metadata;
- database-specific query-dialect guidance for LLM clients;
- module/repository extraction strategy if a second real adapter is later created;
- compatibility/versioning policy for capability evolution.

### 3.3 Explicitly out of scope now

- production Oracle MCP adapter;
- production PostgreSQL MCP adapter;
- cross-database SQL translation;
- ORM or universal query language;
- distributed multi-database routing;
- automatic database-product detection as a substitute for explicit configuration;
- claiming Oracle/PostgreSQL compatibility based only on H2 tests.

## 4. Target architecture

The dependency direction shall be:

```text
MCP inbound adapter
        |
        v
application use cases / ports
        |
        v
domain models + common policy
        ^
        |
database outbound adapter (Db2 now; Oracle/PostgreSQL later)
```

Recommended package shape:

```text
io.github.marekpompura.db2mcp
├── bootstrap
├── configuration
├── domain
│   ├── model
│   ├── capability
│   ├── error
│   └── audit
├── application
│   ├── port
│   │   ├── in
│   │   └── out
│   └── service
├── adapter
│   ├── in
│   │   └── mcp
│   └── out
│       ├── db2
│       └── observability
└── policy
```

The exact names may differ, but the following dependency constraints are mandatory:

- domain must not depend on MCP, Spring AI, JDBC, or a database vendor;
- application ports must not return classes owned by `mcp.*`;
- MCP adapter may map domain/application models into transport-specific results;
- Db2 adapter may depend on JDBC and Db2 catalog semantics;
- Db2 adapter must not depend on MCP annotations or MCP request objects;
- common policy and observability orchestration must not depend on `adapter.db2`;
- database-specific error classification must implement a common error-classification port.

ArchUnit tests shall enforce these rules.

## 5. Database identity and capability discovery

### 5.1 Database descriptor

Define an immutable descriptor similar to:

```java
public record DatabaseDescriptor(
        DatabaseProduct product,
        String productVersion,
        String sqlDialect,
        IdentifierRules identifierRules) {}
```

`DatabaseProduct` may initially contain only `DB2`. Future enum values must not be interpreted as implemented support without an adapter and integration evidence.

Sensitive connection details must never appear in the descriptor. It must not expose hostname, port, database username, JDBC URL, or network topology.

### 5.2 Capability manifest

Expose a read-only MCP tool such as `getCapabilities` returning a versioned manifest:

```json
{
  "contractVersion": "1.0",
  "database": {
    "product": "DB2",
    "productVersion": "11.5",
    "sqlDialect": "DB2_LUW"
  },
  "capabilities": {
    "schemaDiscovery": true,
    "tableDiscovery": true,
    "tableDescription": true,
    "foreignKeyDependencies": true,
    "routineDiscovery": true,
    "routineSource": true,
    "referenceDataLookup": true,
    "readOnlyQuery": true,
    "explainPlan": false
  },
  "limits": {
    "maxRows": 1000,
    "maxCellBytes": 16384,
    "maxPayloadBytes": 1048576
  }
}
```

The manifest must report effective configured capabilities, not merely features present in code. For example, `readOnlyQuery` must be `false` under `METADATA_ONLY`.

The manifest is operational metadata. It must not contain credentials, internal schemas, allowlist contents, hostnames, or internal paths unless a separate explicit policy authorizes such disclosure.

## 6. Capability model

### 6.1 Common capabilities

The following concepts may use stable database-neutral semantics:

- list accessible schemas/namespaces;
- list relations such as tables and views;
- describe relation columns;
- discover referential dependencies;
- list routines;
- describe a specifically identified routine;
- retrieve routine source when available and authorized;
- execute a bounded, read-only query in the advertised dialect;
- perform a governed reference-data lookup;
- report effective server capabilities and limits.

Tool names may remain stable when their semantics are stable. Tool descriptions must avoid falsely promising identical implementation details across databases.

For example:

- preferred common description: `List tables and views in an accessible schema.`
- Db2-specific implementation detail belongs in adapter documentation, not necessarily the generic contract;
- `getProcedureSource` may evolve toward `getRoutineSource` only through a versioned contract decision, not an incidental rename.

### 6.2 Vendor-specific capabilities

Capabilities without equivalent cross-database semantics may remain vendor-specific, for example:

- Db2 package and routine dependency metadata;
- Oracle package bodies and synonyms;
- PostgreSQL extension metadata;
- vendor-specific explain-plan structures;
- Db2 tablespace or buffer-pool information.

Vendor-specific tools must be explicitly namespaced or identified in the capability manifest. The client must not assume their presence.

### 6.3 Unsupported capabilities

Unsupported behavior must be represented in two ways:

1. the tool should be absent from discovery when configuration and framework behavior permit; and
2. defense-in-depth authorization must reject direct invocation through any unintended path.

If a stable tool must remain visible for compatibility, it must return a structured `CAPABILITY_UNSUPPORTED` error, not an empty result that can be mistaken for valid absence of data.

## 7. Application ports and domain models

### 7.1 Ports

Application ports should express intent rather than vendor catalogs. Illustrative examples:

```java
public interface DatabaseMetadataPort {
    List<SchemaDescriptor> listSchemas();
    List<RelationDescriptor> listRelations(SchemaName schema);
    RelationDescription describeRelation(RelationId relation);
    List<ReferentialDependency> findReferentialDependencies(RelationId relation);
}

public interface RoutineMetadataPort {
    List<RoutineDescriptor> listRoutines(SchemaName schema);
    RoutineDescription describeRoutine(RoutineId routine);
    RoutineSource getRoutineSource(RoutineId routine);
}

public interface ReadOnlyQueryPort {
    BoundedQueryResult execute(ReadOnlyQuery request);
}

public interface DatabaseCapabilityPort {
    DatabaseCapabilities capabilities();
}
```

These names are illustrative. Claude should adapt them to the existing codebase with the smallest coherent refactor rather than mechanically creating unnecessary interfaces.

### 7.2 Routine identity

Routine identity must support vendor-specific overload resolution without leaking catalog-row assumptions into the MCP layer.

For Db2, the adapter must retain `SPECIFICNAME`. A neutral identity can contain:

```java
public record RoutineId(
        String schema,
        String name,
        String specificId) {}
```

`specificId` semantics are defined by the active adapter. The capability manifest or result metadata may describe its meaning without exposing internal implementation details.

### 7.3 Result value model

Do not use unbounded `List<List<Object>>` as the long-term domain contract. Define an explicit JSON-safe value representation and bounded-result metadata.

The model must cover:

- null;
- boolean;
- integer and decimal without silent precision loss;
- string;
- date/time/timestamp with documented format and timezone behavior;
- bounded binary representation or explicit binary omission;
- truncated values;
- unsupported JDBC/vendor values.

Every adapter must normalize vendor/JDBC values into this common representation before they reach the MCP adapter.

## 8. SQL dialect policy

The project must not imply that raw SQL is database-neutral.

`executeQuery` may continue accepting SQL text, but:

- the capability manifest must advertise the expected dialect;
- structural validation must be selected/configured for that dialect;
- the database adapter owns vendor-specific execution and error mapping;
- dialect-specific valid read-only syntax may be intentionally rejected if the parser cannot validate it safely;
- fail-closed behavior is preferred to accepting unvalidated syntax;
- contract tests must include common read-only cases and vendor-specific rejection cases.

Cross-database SQL translation is out of scope. The client must generate SQL appropriate to the advertised dialect or prefer higher-level metadata tools.

## 9. Common policy boundary

Authorization and resource governance must apply by capability semantics, not by adapter class.

Every row-data capability, including `lookupRefData`, must pass through common controls for:

- capability profile;
- allowed schema/scope;
- allowed dataset/table/columns where applicable;
- statement/query timeout;
- maximum rows;
- maximum cell size;
- maximum payload size;
- value normalization;
- audit and trace correlation;
- structured error sanitization.

Database adapters may add stricter controls but must not bypass common policy.

Metadata enumeration requires its own explicit scope decision. A schema allowlist used for row queries must not automatically be assumed to have identical semantics for metadata discovery; the two policies should be named and configured clearly.

## 10. Error model

Define common categories independent of a database brand, for example:

- `CAPABILITY_DISABLED`;
- `CAPABILITY_UNSUPPORTED`;
- `VALIDATION_FAILED`;
- `SCOPE_DENIED`;
- `QUERY_TIMEOUT`;
- `AUTHORIZATION_FAILED`;
- `OBJECT_NOT_FOUND`;
- `DATABASE_EXECUTION_FAILED`;
- `SERIALIZATION_FAILED`;
- `INTERNAL_ERROR`.

The Db2 adapter may translate SQLSTATE values into these categories through a `DatabaseErrorClassifier`. A future Oracle adapter may classify Oracle error codes; PostgreSQL may use SQLSTATE. Client-facing errors must remain stable across adapters where semantics match.

Each error should include a safe correlation identifier. Raw SQL, driver messages, stack traces, credentials, and topology must not be returned.

## 11. Adapter selection and configuration

The current implementation shall explicitly select Db2. A suggested configuration is:

```yaml
dbmcp:
  database:
    product: DB2
```

The existing `db2mcp.*` namespace may remain for compatibility during the Db2-only phase, but a decision must be recorded before introducing a second adapter.

Adapter beans should be conditional so that exactly one implementation of each outbound database port is active. Startup must fail with a clear sanitized configuration error if:

- no adapter is selected;
- more than one adapter is active;
- the selected product has no implementation;
- required driver/configuration is missing.

Automatic selection based solely on JDBC URL should not be the primary mechanism because it makes configuration and security intent less explicit.

## 12. Testing requirements

### 12.1 Common contract suite

Create a reusable adapter contract test suite covering semantics independent of a live database:

- stable schema/relation/routine result models;
- bounded query-result behavior;
- capability discovery;
- unsupported-capability behavior;
- common error mapping;
- payload and cell truncation;
- MCP result serialization;
- audit/metrics/span generation.

Use a fake adapter to prove that MCP and application layers do not depend on Db2 classes or `SYSCAT` behavior.

### 12.2 Db2 adapter suite

Retain H2 tests only for generic JDBC and policy behavior. H2 must not be treated as proof of Db2 catalog compatibility.

Live Db2 tests must cover:

- schema and table/view discovery;
- column metadata and Db2 data types;
- primary and unique keys;
- composite foreign keys using the actual referenced key;
- overloaded procedures using `SPECIFICNAME`;
- SQL and non-SQL routine source behavior;
- timeout SQLSTATE behavior;
- read-only session intent;
- insufficient privileges and missing objects;
- CLOB/BLOB/XML and temporal value normalization;
- quoted and mixed-case identifiers where supported.

### 12.3 Future adapter gate

A future database adapter must not be called supported until:

- it passes the common contract suite;
- it has product-specific integration tests against the real database engine;
- its capability manifest accurately reflects supported behavior;
- security controls and error sanitization are verified end to end;
- documentation states tested product versions and known limitations.

## 13. MCP contract versioning

Add a contract version to capability discovery. Version changes must follow these rules:

- adding an optional capability is backward-compatible if clients discover it dynamically;
- removing or renaming a tool is breaking;
- changing required parameters is breaking;
- changing result field semantics or types is breaking;
- adding optional result fields should be documented and snapshot-tested;
- database-specific extensions must not silently change common tool semantics.

Input schemas and representative output payloads must both be snapshot-tested.

## 14. Documentation requirements

The README and architecture documentation must state:

- Db2 is the only implemented reference adapter;
- Oracle and PostgreSQL are architectural extension points, not supported products;
- raw SQL remains dialect-specific;
- common capabilities and Db2-specific capabilities are distinguishable;
- the capability manifest is the runtime source of truth;
- H2 tests do not validate Db2 system catalogs;
- live-Db2 evidence required for release is listed explicitly.

Allowed portfolio statement after the minimum refactor:

> The reference implementation targets IBM Db2 while separating MCP/application contracts from database-specific catalog and execution adapters. A versioned capability manifest and adapter contract tests allow future database implementations without rewriting the governed MCP boundary.

Not allowed until implemented and verified:

> Supports Db2, Oracle, and PostgreSQL.

## 15. Claude implementation instructions

Claude should treat this extension as an architectural constraint, not as permission for a broad rewrite.

### Required working method

1. Read the original master brief, current ADRs, implementation plan, baseline status, and the companion review report.
2. Produce a concise change-impact inventory before editing code.
3. Identify which existing public contracts would change, especially procedure identity and query results.
4. Propose ADR additions/updates before implementing architectural changes.
5. Close P0 review findings before portability refactoring.
6. Refactor in small, independently tested commits.
7. Preserve current Db2 behavior unless the review identifies it as incorrect.
8. Do not add Oracle/PostgreSQL dependencies or placeholder production adapters.
9. Use a fake adapter only to prove dependency inversion and contract portability.
10. Update documentation claims to match verified behavior after each phase.

### Suggested implementation phases

#### Phase P1 — Security boundary correction

- reclassify and govern `lookupRefData`;
- introduce common tool error sanitization;
- introduce common audit/metrics/tracing boundary;
- add payload and value normalization limits.

#### Phase P2 — Db2 correctness

- add `SPECIFICNAME`-based routine identity;
- correct foreign-key parent-key resolution using `REFKEYNAME`;
- add live-Db2 integration-test profile and fixtures.

#### Phase P3 — Dependency inversion

- move neutral models out of `mcp.contract`;
- introduce coherent outbound ports;
- map domain/application results in the MCP inbound adapter;
- move Db2 error classification behind a port;
- extend ArchUnit rules.

#### Phase P4 — Capability discovery and adapter selection

- implement versioned `getCapabilities`;
- expose effective profile and safe limits;
- make Db2 adapter activation explicit;
- fail fast on invalid adapter configuration.

#### Phase P5 — Portability evidence

- implement fake adapter;
- run common adapter/MCP contract tests against Db2 wiring and fake wiring;
- snapshot inputs and representative outputs;
- update architecture diagrams and README wording.

## 16. Definition of done

This extension is complete when:

- application/domain code no longer depends on MCP-owned DTOs;
- no common policy component depends on Db2 adapter classes;
- exactly one explicitly configured database adapter supplies outbound ports;
- a safe versioned capability manifest reports effective functionality and dialect;
- all row-data paths share capability, scope, timeout, row, cell, and payload controls;
- all MCP tools share structured error sanitization, audit, metrics, tracing, and correlation;
- procedure overloads are represented unambiguously;
- Db2 foreign-key dependencies use the actual referenced key;
- fake-adapter contract tests prove that the MCP/application layers are not Db2-bound;
- live-Db2 tests prove Db2 catalog behavior;
- Oracle/PostgreSQL are still described only as future adapters;
- documentation and portfolio claims match the evidence.

## 17. Architectural decision recommendation

Keep the current repository and product identity as **Governed Db2 MCP Server**. Do not rename it to a generic database server during the first release.

Internally, make the project adapter-ready. If a second production adapter is later implemented, revisit repository topology through a dedicated ADR. Likely options will be:

1. a multi-module repository with common core and database adapter modules; or
2. separate product repositories sharing a small versioned contract library.

That decision should be evidence-driven after a second adapter exists. Creating a generic multi-module platform now would add complexity without proving portability.
