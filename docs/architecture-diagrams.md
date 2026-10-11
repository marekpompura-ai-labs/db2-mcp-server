# Architecture and flow diagrams

Master brief section 14's required diagram set, drawn against this
codebase's actual package names, class names, and span names (verified
directly in `Db2QueryAdapter.java`, not reconstructed from memory) rather
than the brief's generic sketch. GitHub renders Mermaid natively; no
export step is required to view these, though PNG/SVG export remains an
option if a non-GitHub presentation context needs it later.

## 14.1 System/component view

```mermaid
flowchart TD
    Client["MCP client<br/>e.g. Claude Code"] --> Transport["Stdio transport<br/>ADR 0001 - stdout is JSON-RPC only"]
    Transport --> Tools["mcp.tool<br/>SchemaTools / QueryTools / CapabilityTools"]
    Tools --> App["application.port.out<br/>MetadataPort / QueryPort / CapabilityPort (ADR 0027, ADR 0028)"]
    App --> Policy["policy<br/>CapabilityProfile, SqlStructuralValidator,<br/>SchemaAllowlistValidator"]
    Policy --> Adapter["adapter.db2<br/>Db2MetadataAdapter / Db2QueryAdapter / Db2CapabilityAdapter"]
    Adapter --> DB[("IBM Db2<br/>least-privilege read-only principal")]
    Tools --> Telemetry["adapter.observability<br/>AuditLogger, MetricsRecorder, SpanRecorder"]
    Policy --> Telemetry
    Adapter --> Telemetry
```

Trust boundary: everything left of `adapter.db2` runs in-process with no
network listener of its own (ADR 0001, ADR 0011, ADR 0012 all
independently re-verified this via `lsof` against the packaged jar). The
Db2 credential and the read-only principal it authenticates as are the
only things crossing a real trust boundary, and ADR 0002 enforces
read-only at three independent points along that boundary, not just one.

Everything to the left of the `application.port.out` boundary (`mcp.tool`
and `application`) is provably substitutable, not just diagrammed that
way: `FakeDatabaseAdapter` (test-only, `src/test/.../testsupport/fake`,
zero JDBC/Db2 imports) implements `MetadataPort`/`QueryPort` and is wired
directly into the real `SchemaTools`/`QueryTools`/`ReferenceDataTools`
classes shown above, with all nine backed tool methods exercised end to
end (`FakeAdapterMcpToolsTest`, ADR 0029). It's deliberately absent from
the diagram itself - it's evidence for the boundary's correctness, not a
second production component.

## 14.2 Allowed query request

```mermaid
sequenceDiagram
    participant Client as MCP client
    participant Tool as QueryTools.executeQuery
    participant Adapter as Db2QueryAdapter
    participant Db2 as Db2 (JDBC)
    participant Obs as Audit / metrics / spans

    Client->>Tool: executeQuery(sql)
    Tool->>Adapter: executeQuery(sql)
    Adapter->>Adapter: capability.authorize
    Adapter->>Adapter: input.validate + sql.validate
    Adapter->>Adapter: scope.authorize
    Adapter->>Db2: db2.execute (row-capped, statement timeout)
    Db2-->>Adapter: ResultSet
    Adapter->>Obs: AuditEvent + metrics + span tree (ADR 0010/0011/0012)
    Adapter-->>Tool: QueryResult (columns, rows, rowCount, truncated)
    Tool-->>Client: QueryResult
```

Span names (`capability.authorize`, `input.validate`, `sql.validate`,
`scope.authorize`, `db2.execute`) are copied verbatim from
`Db2QueryAdapter.executeQuery`, not paraphrased - what a real trace
viewer shows is exactly this.

## 14.3 Rejected malicious request

```mermaid
flowchart TD
    Request["executeQuery(sql)"] --> Capability{"Capability profile allows<br/>query execution? (ADR 0006)"}
    Capability -->|No| RejectCap["CAPABILITY_DISABLED"]
    Capability -->|Yes| Structural{"Single, structurally valid<br/>SELECT/WITH? (ADR 0007)"}
    Structural -->|No| RejectInvalid["INVALID_STATEMENT"]
    Structural -->|Yes| Scope{"Every table reference in an<br/>allowed schema? (ADR 0008)"}
    Scope -->|No| RejectScope["SCHEMA_NOT_ALLOWED"]
    Scope -->|Yes| Execute["Execute as read-only Db2 principal"]
    RejectCap --> Audit["Sanitized error to client +<br/>AuditEvent with PolicyDecisionReason<br/>never raw SQL, never row data (ADR 0010)"]
    RejectInvalid --> Audit
    RejectScope --> Audit
```

The three rejection reasons shown are the real `PolicyDecisionReason` enum
values (`domain/audit/PolicyDecisionReason.java`) that appear in audit
log lines and in `docs/security/adversarial-test-cases.md`'s test names -
not illustrative labels invented for this diagram.

## 14.4 Read-only defense in depth

```mermaid
flowchart TD
    Call["executeQuery(sql)"] --> App["Layer 3: SqlStructuralValidator<br/>rejects anything but a single SELECT/WITH"]
    App --> Session["Layer 2: HikariCP read-only connection<br/>spring.datasource.hikari.read-only: true"]
    Session --> Principal["Layer 1: least-privilege Db2 principal<br/>SELECT/EXECUTE grants only (ADR 0002)"]
    Principal --> Data[("Allowlisted Db2 objects only (ADR 0008)")]
    App --> Deny["Fail closed on unsupported input"]
    Session --> Deny
    Principal --> Deny
```

Three independent layers, deliberately redundant with each other (ADR
0002): a bug or bypass in any single layer still leaves the other two
standing, rather than one check with a single point of failure.

## 14.5 Traceability and observability

```mermaid
flowchart TD
    Request["executeQuery + correlation ID"] --> Spans["SpanRecorder: guard-chain spans<br/>capability.authorize -&gt; input.validate -&gt;<br/>sql.validate -&gt; scope.authorize -&gt; db2.execute (ADR 0012)"]
    Spans --> Query["Bounded Db2 execution<br/>row cap + statement timeout (ADR 0007)"]
    Query --> Response["Typed QueryResult or sanitized error"]
    Spans --> Audit["AuditLogger: PolicyDecisionReason +<br/>SQL fingerprint, never raw SQL (ADR 0010)"]
    Spans --> Metrics["MetricsRecorder: Micrometer counters/timers,<br/>low-cardinality tags only (ADR 0011)"]
```

Both `AuditLogger` (a file, ADR 0026) and `SpanRecorder`/`MetricsRecorder`
(in-process instrumentation) are genuinely exported and observable, not
just instrumented: a local logging `MeterRegistry` and `SpanExporter`
write every meter and span to stderr, verified against the real
packaged jar (ADR 0030).

## 14.6 AI-driven development lifecycle

```mermaid
flowchart TD
    Issue["Requirement"] --> Proposal["AI-drafted ADR<br/>Proposed, written before code"]
    Proposal --> Review["Human review of the decision"]
    Review --> Implement["AI-assisted implementation"]
    Implement --> Gates["mvn verify: unit + ArchUnit +<br/>MCP contract snapshot + JaCoCo policy gate"]
    Gates -->|Failed| Implement
    Gates -->|Passed| Human["Human diff + security review"]
    Human --> Decision["ADR marked Accepted,<br/>Consequences updated with real findings"]
    Decision --> Release["Commit / release"]
```

This is this project's own actual practice, not an aspirational
illustration - every Accepted ADR in `docs/adr/` follows exactly this
loop, including the "Consequences updated with real findings" step (e.g.
ADR 0007's `parseStatements` discovery, ADR 0014's `BUNDLE`-vs-`PACKAGE`
finding, ADR 0016's stdin-EOF bug).

## 14.7 Public/private repository topology

```mermaid
flowchart TD
    Private["Private repo:<br/>full-featured development lab"] -->|"curated, sanitized promotion<br/>(brief section 15.1)"| Public["Public repo (this one):<br/>governed core"]
    Public --> Demo["Public ITSM demo"]
    Public --> CI["Public CI: build, tests,<br/>dependency/secret scan"]
    Private --> Internal["Internal-only: experimental adapters,<br/>environment-specific configuration"]
```

Public history is produced by promoting curated, sanitized content
forward - not by deleting private material after the fact. This public
edition begins with a single curated snapshot commit; brief section 15.1's
promotion workflow governs everything after it. See
`docs/master-brief.md` section 15.1 for the full workflow this diagram
summarizes.
