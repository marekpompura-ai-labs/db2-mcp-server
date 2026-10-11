package io.github.marekpompura.db2mcp.adapter.db2;

import io.github.marekpompura.db2mcp.adapter.observability.AuditLogger;
import io.github.marekpompura.db2mcp.adapter.observability.MetricsRecorder;
import io.github.marekpompura.db2mcp.adapter.observability.SpanRecorder;
import io.github.marekpompura.db2mcp.application.port.out.MetadataPort;
import io.github.marekpompura.db2mcp.domain.audit.AuditEvent;
import io.github.marekpompura.db2mcp.domain.audit.PolicyDecisionReason;
import io.github.marekpompura.db2mcp.domain.error.DatabaseErrorClassifier;
import io.github.marekpompura.db2mcp.domain.error.ErrorCategory;
import io.github.marekpompura.db2mcp.domain.error.McpToolException;
import io.github.marekpompura.db2mcp.domain.model.BoundedList;
import io.github.marekpompura.db2mcp.domain.model.ColumnInfo;
import io.github.marekpompura.db2mcp.domain.model.ProcedureDetail;
import io.github.marekpompura.db2mcp.domain.model.ProcedureInfo;
import io.github.marekpompura.db2mcp.domain.model.ProcedureParameterInfo;
import io.github.marekpompura.db2mcp.domain.model.ProcedureSource;
import io.github.marekpompura.db2mcp.domain.model.ReferenceDataEntry;
import io.github.marekpompura.db2mcp.domain.model.SchemaInfo;
import io.github.marekpompura.db2mcp.domain.model.TableDependencyInfo;
import io.github.marekpompura.db2mcp.domain.model.TableInfo;
import io.github.marekpompura.db2mcp.policy.capability.CapabilityProfile;
import io.github.marekpompura.db2mcp.policy.capability.CapabilityProperties;
import io.github.marekpompura.db2mcp.policy.sql.AdvancedQueryLimitsProperties;
import io.github.marekpompura.db2mcp.policy.sql.QueryLimitsProperties;
import io.github.marekpompura.db2mcp.policy.sql.ResolvedQueryLimits;
import io.github.marekpompura.db2mcp.policy.sql.SchemaAllowlistValidator;
import io.micrometer.tracing.Span;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Schema/procedure catalog access via DB2's SYSCAT views. Column names and
 * value codes below were verified against IBM's published catalog view
 * reference before writing this SQL (not from memory) - see ADR 0003/0004
 * discussion. Not yet exercised against a live DB2 instance; only compiled
 * and unit-tested with mocks (see docs/implementation-plan.md Phase 2).
 *
 * <p>Every method sanitizes {@link DataAccessException} into a structured
 * {@link McpToolException} before it can reach the MCP client (CR-002 from
 * the 2026-08-14 code review) - raw driver messages can otherwise disclose
 * object names, connection details, or a full stack trace, contradicting
 * the threat model's untrusted-client assumption. {@link #lookupRefData}
 * additionally enforces the same capability, schema-scope, row-limit, and
 * audit/metrics/span treatment {@code Db2QueryAdapter} applies to {@code
 * executeQuery} (CR-001): it returns caller-selected row data, not
 * metadata, so it cannot rely on identifier-syntax checking alone.
 *
 * <p>Procedure lookups key on {@code SPECIFICNAME}, not {@code PROCNAME}
 * (CR-003, ADR 0022): DB2 allows multiple specific routines to share a
 * schema and name with different signatures, which {@code PROCNAME} alone
 * cannot disambiguate. {@link #getTableDependencies} resolves the
 * referenced key via {@code SYSCAT.REFERENCES.REFKEYNAME} (CR-004, ADR
 * 0022), not an assumption that the referenced key is always a primary
 * key - a foreign key can reference a unique constraint instead.
 *
 * <p>Every method - not just {@link #lookupRefData} - now shares the same
 * audit/metrics/span treatment {@code executeQuery} has always had (CR-009,
 * 2026-08-14 code review, ADR 0025): before this, a call to e.g. {@link
 * #describeTable} left no trace anywhere - no audit line, no metric, no
 * span - despite the master brief's end-to-end traceability requirement
 * applying to every tool, not only the two row-data ones. Every thrown
 * {@link McpToolException} also now carries the same {@code correlationId}
 * its audit line does (CR-012), so a caller reporting a failed call has
 * something to hand back for finding the matching server-side record.
 */
@Component
public class Db2MetadataAdapter implements MetadataPort {

    // SYSCAT identifiers allowed for dynamic SQL concatenation (ADR 0002 layer 3).
    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z0-9_#$@]+$");

    private static final String LIST_SCHEMAS_SQL = """
            SELECT SCHEMANAME
            FROM SYSCAT.SCHEMATA
            WHERE SCHEMANAME NOT LIKE 'SYS%'
              AND SCHEMANAME NOT IN ('NULLID', 'SQLJ')
            ORDER BY SCHEMANAME
            """;

    private static final String LIST_TABLES_SQL = """
            SELECT TABSCHEMA, TABNAME, TYPE
            FROM SYSCAT.TABLES
            WHERE TABSCHEMA = ?
              AND TYPE IN ('T', 'V')
            ORDER BY TABNAME
            """;

    private static final String DESCRIBE_TABLE_SQL = """
            SELECT c.COLNAME, c.TYPENAME, c.LENGTH, c.SCALE, c.NULLS, c.COLNO,
                   CASE WHEN k.COLNAME IS NOT NULL THEN 'Y' ELSE 'N' END AS IS_PK
            FROM SYSCAT.COLUMNS c
            LEFT JOIN (
                SELECT kcu.TABSCHEMA, kcu.TABNAME, kcu.COLNAME
                FROM SYSCAT.KEYCOLUSE kcu
                JOIN SYSCAT.TABCONST tc
                  ON tc.CONSTNAME = kcu.CONSTNAME
                 AND tc.TABSCHEMA = kcu.TABSCHEMA
                 AND tc.TABNAME = kcu.TABNAME
                 AND tc.TYPE = 'P'
            ) k ON k.TABSCHEMA = c.TABSCHEMA AND k.TABNAME = c.TABNAME AND k.COLNAME = c.COLNAME
            WHERE c.TABSCHEMA = ? AND c.TABNAME = ?
            ORDER BY c.COLNO
            """;

    // One row per (constraint, column) pair; see TableDependencyInfo javadoc for why
    // this avoids LISTAGG. FK/PK column pairing assumes COLSEQ order corresponds
    // between the FK's key-use rows and the referenced key's key-use rows, which
    // is DB2's standard convention for composite keys.
    //
    // Joins the referenced key via r.REFKEYNAME, not a SYSCAT.TABCONST lookup
    // filtered to TYPE = 'P' (CR-004, 2026-08-14 code review, ADR 0022): a
    // foreign key can reference a unique constraint, not only a primary key,
    // and REFKEYNAME already names the exact constraint DB2 resolved it
    // against - filtering to primary keys only silently dropped or
    // misresolved those relationships. Tier 2 (ADR 0009): SYSCAT.REFERENCES
    // .REFKEYNAME is documented DB2 LUW catalog behavior, not yet verified
    // against a live instance in this environment.
    private static final String TABLE_DEPENDENCIES_SQL = """
            SELECT r.CONSTNAME, 'OUTBOUND' AS DIRECTION,
                   r.TABSCHEMA AS FK_SCHEMA, r.TABNAME AS FK_TABLE, fk.COLNAME AS FK_COLUMN,
                   r.REFTABSCHEMA AS PK_SCHEMA, r.REFTABNAME AS PK_TABLE, pk.COLNAME AS PK_COLUMN
            FROM SYSCAT.REFERENCES r
            JOIN SYSCAT.KEYCOLUSE fk
              ON fk.CONSTNAME = r.CONSTNAME AND fk.TABSCHEMA = r.TABSCHEMA AND fk.TABNAME = r.TABNAME
            JOIN SYSCAT.KEYCOLUSE pk
              ON pk.CONSTNAME = r.REFKEYNAME AND pk.TABSCHEMA = r.REFTABSCHEMA
             AND pk.TABNAME = r.REFTABNAME AND pk.COLSEQ = fk.COLSEQ
            WHERE r.TABSCHEMA = ? AND r.TABNAME = ?
            UNION ALL
            SELECT r.CONSTNAME, 'INBOUND' AS DIRECTION,
                   r.TABSCHEMA AS FK_SCHEMA, r.TABNAME AS FK_TABLE, fk.COLNAME AS FK_COLUMN,
                   r.REFTABSCHEMA AS PK_SCHEMA, r.REFTABNAME AS PK_TABLE, pk.COLNAME AS PK_COLUMN
            FROM SYSCAT.REFERENCES r
            JOIN SYSCAT.KEYCOLUSE fk
              ON fk.CONSTNAME = r.CONSTNAME AND fk.TABSCHEMA = r.TABSCHEMA AND fk.TABNAME = r.TABNAME
            JOIN SYSCAT.KEYCOLUSE pk
              ON pk.CONSTNAME = r.REFKEYNAME AND pk.TABSCHEMA = r.REFTABSCHEMA
             AND pk.TABNAME = r.REFTABNAME AND pk.COLSEQ = fk.COLSEQ
            WHERE r.REFTABSCHEMA = ? AND r.REFTABNAME = ?
            ORDER BY 2, 1, 5
            """;

    // SPECIFICNAME (SYSCAT.PROCEDURES.SPECIFICNAME) uniquely identifies one
    // overload; PROCSCHEMA+PROCNAME alone does not (CR-003, 2026-08-14 code
    // review, ADR 0022) - DB2 allows multiple specific routines sharing a
    // schema and name with different signatures. listProcedures still keys
    // by PROCSCHEMA alone (one row per specific routine already, since
    // SYSCAT.PROCEDURES's grain is per-specific-routine), but every
    // subsequent lookup uses SPECIFICNAME, not PROCNAME.
    private static final String LIST_PROCEDURES_SQL = """
            SELECT PROCSCHEMA, PROCNAME, SPECIFICNAME, LANGUAGE, PARM_COUNT
            FROM SYSCAT.PROCEDURES
            WHERE PROCSCHEMA = ?
            ORDER BY PROCNAME, SPECIFICNAME
            """;

    private static final String PROCEDURE_INFO_SQL = """
            SELECT PROCSCHEMA, PROCNAME, SPECIFICNAME, LANGUAGE, PARM_COUNT
            FROM SYSCAT.PROCEDURES
            WHERE PROCSCHEMA = ? AND SPECIFICNAME = ?
            """;

    private static final String PROCEDURE_PARAMS_SQL = """
            SELECT PARMNAME, TYPENAME, PARM_MODE, ORDINAL
            FROM SYSCAT.PROCPARMS
            WHERE PROCSCHEMA = ? AND SPECIFICNAME = ?
            ORDER BY ORDINAL
            """;

    private static final String PROCEDURE_SOURCE_SQL = """
            SELECT PROCSCHEMA, PROCNAME, SPECIFICNAME, TEXT
            FROM SYSCAT.PROCEDURES
            WHERE PROCSCHEMA = ? AND SPECIFICNAME = ?
            """;

    private static final String LOOKUP_REF_DATA_TOOL_NAME = "lookupRefData";

    private final JdbcTemplate jdbcTemplate;
    private final JdbcTemplate lookupRefDataJdbcTemplate;
    private final CapabilityProfile capabilityProfile;
    private final SchemaAllowlistValidator schemaAllowlistValidator;
    private final AuditLogger auditLogger;
    private final MetricsRecorder metricsRecorder;
    private final SpanRecorder spanRecorder;
    private final DatabaseErrorClassifier errorClassifier;
    private final int maxRows;
    private final int maxCellBytes;
    private final int maxPayloadBytes;

    public Db2MetadataAdapter(
            DataSource dataSource,
            CapabilityProperties capabilityProperties,
            SchemaAllowlistValidator schemaAllowlistValidator,
            QueryLimitsProperties queryLimitsProperties,
            AdvancedQueryLimitsProperties advancedQueryLimitsProperties,
            AuditLogger auditLogger,
            MetricsRecorder metricsRecorder,
            SpanRecorder spanRecorder,
            DatabaseErrorClassifier errorClassifier) {
        this.capabilityProfile = capabilityProperties.capabilityProfile();
        // P0-2/P1-1 (2026-09-01 master-brief gap review): resolved through
        // the same ResolvedQueryLimits abstraction Db2QueryAdapter uses, not
        // always QueryLimitsProperties's SAFE_QUERY-tier numbers regardless
        // of profile - lookupRefData previously always applied SAFE_QUERY
        // limits even under ADVANCED_QUERY, disagreeing with what
        // getCapabilities advertised.
        ResolvedQueryLimits resolvedLimits =
                ResolvedQueryLimits.of(capabilityProfile, queryLimitsProperties, advancedQueryLimitsProperties);
        this.maxRows = resolvedLimits.maxRows();
        this.maxCellBytes = resolvedLimits.maxCellBytes();
        this.maxPayloadBytes = resolvedLimits.maxPayloadBytes();
        // P0-2: the seven pure metadata methods previously had neither a row
        // cap nor a query timeout at all (only lookupRefData did) - an
        // unbounded catalog enumeration could return arbitrarily many rows.
        // A dedicated JdbcTemplate, not the shared bean, so these are fixed
        // at construction (same reasoning as Db2QueryAdapter's own instance).
        // maxRows + 1, the same trick lookupRefData already used (#62,
        // 2026-09-09 re-review): reading one row past the cap is what makes
        // "there were more" distinguishable from "there were exactly this
        // many", so #boundedList can slice back to maxRows and report
        // truncated honestly. Under the previous exactly-maxRows cap these
        // six methods could not detect their own truncation at all - a
        // residual gap this file's own comment used to name.
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.jdbcTemplate.setMaxRows(maxRows + 1);
        this.jdbcTemplate.setQueryTimeout(resolvedLimits.timeoutSeconds());
        // A second, separately configured JdbcTemplate for lookupRefData -
        // same resolved limits, kept distinct because lookupRefData's own
        // scope/capability gating and row-truncation reporting (see
        // #lookupRefData) are handled inline, not through #instrumented.
        this.lookupRefDataJdbcTemplate = new JdbcTemplate(dataSource);
        this.lookupRefDataJdbcTemplate.setMaxRows(maxRows + 1);
        this.lookupRefDataJdbcTemplate.setQueryTimeout(resolvedLimits.timeoutSeconds());
        this.schemaAllowlistValidator = schemaAllowlistValidator;
        this.auditLogger = auditLogger;
        this.metricsRecorder = metricsRecorder;
        this.spanRecorder = spanRecorder;
        this.errorClassifier = errorClassifier;
    }

    @Override
    public BoundedList<SchemaInfo> listSchemas() {
        // P0-1 (2026-09-01 master-brief gap review): filtered to the configured
        // allowlist, not every non-system schema DB2 reports - an unfiltered
        // listSchemas is itself the enumeration half of the scope bypass the
        // per-schema checks below close for every other method. An empty
        // allowlist therefore means an empty result here too, the same
        // fail-closed default executeQuery/lookupRefData already apply.
        return boundedList("listSchemas", null, fingerprintOf("SCHEMAS"), () -> jdbcTemplate
                .query(LIST_SCHEMAS_SQL, Db2MetadataAdapter::mapSchema)
                .stream()
                .filter(s -> schemaAllowlistValidator.isAllowed(s.schemaName()))
                .toList());
    }

    @Override
    public BoundedList<TableInfo> listTables(String schema) {
        return boundedList(
                "listTables",
                schema,
                fingerprintOf("TABLES", schema),
                () -> jdbcTemplate.query(LIST_TABLES_SQL, Db2MetadataAdapter::mapTable, schema));
    }

    @Override
    public BoundedList<ColumnInfo> describeTable(String schema, String table) {
        return boundedList(
                "describeTable",
                schema,
                fingerprintOf("TABLE", schema, table),
                () -> jdbcTemplate.query(DESCRIBE_TABLE_SQL, Db2MetadataAdapter::mapColumn, schema, table));
    }

    @Override
    public BoundedList<TableDependencyInfo> getTableDependencies(String schema, String table) {
        return boundedList(
                "getTableDependencies",
                schema,
                fingerprintOf("DEPENDENCIES", schema, table),
                () -> jdbcTemplate.query(
                        TABLE_DEPENDENCIES_SQL, Db2MetadataAdapter::mapDependency, schema, table, schema, table));
    }

    @Override
    public BoundedList<ProcedureInfo> listProcedures(String schema) {
        return boundedList(
                "listProcedures",
                schema,
                fingerprintOf("PROCEDURES", schema),
                () -> jdbcTemplate.query(LIST_PROCEDURES_SQL, Db2MetadataAdapter::mapProcedure, schema));
    }

    @Override
    public ProcedureDetail describeProcedure(String schema, String specificName) {
        return instrumented("describeProcedure", schema, fingerprintOf("PROCEDURE", schema, specificName), () -> {
            List<ProcedureInfo> infos =
                    jdbcTemplate.query(PROCEDURE_INFO_SQL, Db2MetadataAdapter::mapProcedure, schema, specificName);
            if (infos.isEmpty()) {
                throw new NotFoundException("No such procedure: " + schema + "." + specificName);
            }
            List<ProcedureParameterInfo> parameters = jdbcTemplate.query(
                    PROCEDURE_PARAMS_SQL, Db2MetadataAdapter::mapParameter, schema, specificName);
            return new ProcedureDetail(infos.get(0), parameters);
        });
    }

    @Override
    public ProcedureSource getProcedureSource(String schema, String specificName) {
        return instrumented("getProcedureSource", schema, fingerprintOf("SOURCE", schema, specificName), () -> {
            // P0-2 (2026-09-01 review): TEXT is a procedure body, unbounded
            // in principle - bounded to maxCellBytes the same way any other
            // large string cell is, instead of returning it whole.
            List<ProcedureSource> sources = jdbcTemplate.query(
                    PROCEDURE_SOURCE_SQL, (rs, rowNum) -> mapSource(rs, maxCellBytes), schema, specificName);
            if (sources.isEmpty()) {
                throw new NotFoundException("No such procedure: " + schema + "." + specificName);
            }
            return sources.get(0);
        });
    }

    @Override
    public BoundedList<ReferenceDataEntry> lookupRefData(
            String schema, String table, String codeColumn, String descriptionColumn,
            String filterColumn, String filterValue) {
        String correlationId = UUID.randomUUID().toString();
        long start = System.nanoTime();
        String fingerprint = fingerprintOf("REFDATA", schema, table);
        Span rootSpan = spanRecorder.startRoot("mcp.tool " + LOOKUP_REF_DATA_TOOL_NAME);
        rootSpan.tag("tool", LOOKUP_REF_DATA_TOOL_NAME);
        rootSpan.tag("correlationId", correlationId);

        try {
            try {
                spanRecorder.inChildSpan(
                        rootSpan, "capability.authorize", () -> capabilityProfile.requireQueryExecutionAllowed("lookupRefData"));
            } catch (RuntimeException e) {
                recordOutcome(
                        LOOKUP_REF_DATA_TOOL_NAME,
                        correlationId,
                        PolicyDecisionReason.CAPABILITY_DISABLED,
                        fingerprint,
                        start,
                        null,
                        null,
                        null);
                throw new McpToolException(ErrorCategory.CAPABILITY_DISABLED, e.getMessage(), null, correlationId);
            }

            try {
                spanRecorder.inChildSpan(rootSpan, "input.validate", () -> {
                    requireIdentifier(schema);
                    requireIdentifier(table);
                    requireIdentifier(codeColumn);
                    requireIdentifier(descriptionColumn);
                    if (filterColumn != null && !filterColumn.isBlank()) {
                        requireIdentifier(filterColumn);
                    }
                });
            } catch (RuntimeException e) {
                recordOutcome(
                        LOOKUP_REF_DATA_TOOL_NAME,
                        correlationId,
                        PolicyDecisionReason.INVALID_STATEMENT,
                        fingerprint,
                        start,
                        null,
                        null,
                        null);
                // P0-3 (2026-09-01 review): requireIdentifier embeds the raw
                // caller-supplied identifier in its exception message - not
                // forwarded to the MCP client, same treatment as every other
                // validator failure in this project's guard chains.
                throw new McpToolException(
                        ErrorCategory.VALIDATION_FAILED, "One or more identifiers failed validation", null, correlationId);
            }

            try {
                spanRecorder.inChildSpan(rootSpan, "scope.authorize", () -> schemaAllowlistValidator.validateSchema(schema));
            } catch (RuntimeException e) {
                recordOutcome(
                        LOOKUP_REF_DATA_TOOL_NAME,
                        correlationId,
                        PolicyDecisionReason.SCHEMA_NOT_ALLOWED,
                        fingerprint,
                        start,
                        null,
                        null,
                        null);
                throw new McpToolException(
                        ErrorCategory.SCOPE_DENIED, "Schema is not in the configured allowlist", null, correlationId);
            }

            long db2Start = System.nanoTime();
            try {
                List<ReferenceDataEntry> entries = spanRecorder.inChildSpan(
                        rootSpan, "db2.execute", () -> executeLookup(schema, table, codeColumn, descriptionColumn, filterColumn, filterValue));
                boolean rowCapTruncated = entries.size() > maxRows;
                if (rowCapTruncated) {
                    entries = entries.subList(0, maxRows);
                }
                // #62 (2026-09-09 re-review, RR-P0-1): the whole response,
                // envelope included, is what gets measured against
                // maxPayloadBytes now - and the caller is told, through the
                // returned envelope's own truncated flag, when it bit. The
                // previous version summed entry sizes independently and
                // returned a bare list, so a caller could not tell a
                // complete result from a cut one even though the server had
                // already calculated and audited the difference.
                PayloadBudget.Fitted<BoundedList<ReferenceDataEntry>> fitted = PayloadBudget.fit(
                        entries,
                        rowCapTruncated,
                        maxPayloadBytes,
                        (kept, truncated) -> BoundedList.of(kept, truncated, correlationId));
                metricsRecorder.recordResultBytes(LOOKUP_REF_DATA_TOOL_NAME, fitted.byteLength());
                metricsRecorder.recordDb2QueryDuration(LOOKUP_REF_DATA_TOOL_NAME, millisSince(db2Start));
                recordOutcome(
                        LOOKUP_REF_DATA_TOOL_NAME,
                        correlationId,
                        PolicyDecisionReason.ALLOWED,
                        fingerprint,
                        start,
                        fitted.result().rowCount(),
                        fitted.truncated(),
                        null);
                return fitted.result();
            } catch (PayloadBudget.BudgetExceededException e) {
                metricsRecorder.recordDb2QueryDuration(LOOKUP_REF_DATA_TOOL_NAME, millisSince(db2Start));
                recordOutcome(
                        LOOKUP_REF_DATA_TOOL_NAME,
                        correlationId,
                        PolicyDecisionReason.PAYLOAD_LIMIT_EXCEEDED,
                        fingerprint,
                        start,
                        null,
                        null,
                        null);
                throw new McpToolException(
                        ErrorCategory.RESULT_LIMIT_EXCEEDED,
                        "Result exceeds the configured maximum response size",
                        null,
                        correlationId);
            } catch (PayloadBudget.SizingException e) {
                // A response that cannot be serialized cannot be measured,
                // and an unmeasurable response must not be sent (#62): the
                // predecessor's `return 0` treated exactly this case as
                // costing nothing. INTERNAL_ERROR, not RESULT_LIMIT_EXCEEDED
                // - nothing is known about its size, only that the size is
                // unknowable, and reporting it as "too large" would invite
                // the operator to raise a limit that is not the problem.
                metricsRecorder.recordDb2QueryDuration(LOOKUP_REF_DATA_TOOL_NAME, millisSince(db2Start));
                recordOutcome(
                        LOOKUP_REF_DATA_TOOL_NAME,
                        correlationId,
                        PolicyDecisionReason.EXECUTION_FAILED,
                        fingerprint,
                        start,
                        null,
                        null,
                        null);
                throw new McpToolException(
                        ErrorCategory.INTERNAL_ERROR, "Result could not be serialized", null, correlationId);
            } catch (DataAccessException e) {
                // Same sanitization discipline as Db2QueryAdapter.executeQuery: only
                // SQLSTATE crosses the MCP boundary, never the raw driver message.
                String sqlState = Db2Exceptions.extractSqlState(e);
                metricsRecorder.recordDb2QueryDuration(LOOKUP_REF_DATA_TOOL_NAME, millisSince(db2Start));
                metricsRecorder.recordDb2Error(sqlState);
                recordOutcome(
                        LOOKUP_REF_DATA_TOOL_NAME,
                        correlationId,
                        PolicyDecisionReason.EXECUTION_FAILED,
                        fingerprint,
                        start,
                        null,
                        null,
                        sqlState);
                throw new McpToolException(
                        errorClassifier.classify(sqlState), "Reference data lookup failed", sqlState, correlationId);
            }
        } finally {
            rootSpan.end();
        }
    }

    private List<ReferenceDataEntry> executeLookup(
            String schema, String table, String codeColumn, String descriptionColumn,
            String filterColumn, String filterValue) {
        if (filterColumn != null && !filterColumn.isBlank()) {
            String sql = "SELECT %s AS CODE, %s AS DESCRIPTION FROM %s.%s WHERE %s = ? ORDER BY %s"
                    .formatted(codeColumn, descriptionColumn, schema, table, filterColumn, codeColumn);
            return lookupRefDataJdbcTemplate.query(sql, (rs, rowNum) -> mapRefData(rs, maxCellBytes), filterValue);
        }
        String sql = "SELECT %s AS CODE, %s AS DESCRIPTION FROM %s.%s ORDER BY %s"
                .formatted(codeColumn, descriptionColumn, schema, table, codeColumn);
        return lookupRefDataJdbcTemplate.query(sql, (rs, rowNum) -> mapRefData(rs, maxCellBytes));
    }

    /**
     * The list-producing metadata methods: row-cap, then fit the complete
     * response to {@code maxPayloadBytes}, and report both kinds of
     * truncation through the returned envelope (#62, 2026-09-09 re-review,
     * RR-P0-1). The row cap reads one row past {@code maxRows} (see the
     * constructor) purely so "there were more" is distinguishable here.
     */
    private <E> BoundedList<E> boundedList(String toolName, String schema, String fingerprint, Supplier<List<E>> work) {
        return instrumentedWith(toolName, schema, fingerprint, work, (rows, correlationId) -> {
            boolean rowCapTruncated = rows.size() > maxRows;
            List<E> capped = rowCapTruncated ? rows.subList(0, maxRows) : rows;
            PayloadBudget.Fitted<BoundedList<E>> fitted = PayloadBudget.fit(
                    capped,
                    rowCapTruncated,
                    maxPayloadBytes,
                    (kept, truncated) -> BoundedList.of(kept, truncated, correlationId));
            return new Finished<>(
                    fitted.result(), fitted.byteLength(), fitted.result().rowCount(), fitted.truncated());
        });
    }

    /**
     * Shared audit/metrics/span treatment for the seven pure metadata
     * methods (CR-009), now also schema-scope-gated (P0-1, 2026-09-01
     * master-brief gap review) for every method that takes a caller-supplied
     * schema - the same {@link SchemaAllowlistValidator} allowlist {@code
     * executeQuery}/{@code lookupRefData} already enforce, closing the gap
     * where these methods were "visible under every profile" (as the prior
     * version of this javadoc put it) but not scope-checked at all. A
     * rejected schema is recorded as {@code SCHEMA_NOT_ALLOWED} and never
     * reaches {@code work} - no query runs, no catalog data is touched.
     * {@code null schema} (only {@link #listSchemas()}) skips this check
     * entirely, since there is no single schema to validate.
     *
     * <p>The generic {@code McpToolException} message below never forwards
     * {@code SchemaAllowlistValidator}'s own exception message (P0-3, same
     * review): that message is server-log diagnostic detail, not something
     * safe to hand to an MCP client - see {@code SchemaAllowlistValidator}'s
     * javadoc and this project's {@code docs/reviews/} disposition of P0-3.
     */
    private <T> T instrumented(String toolName, String schema, String fingerprint, Supplier<T> work) {
        // Single-object results have no list to trim, so an over-budget one
        // is refused rather than silently sent (#62): these two tools are
        // the likeliest to carry a large blob of procedure DDL, which is
        // exactly the case a "hard" bound has to cover to deserve the name.
        return instrumentedWith(toolName, schema, fingerprint, work, (raw, correlationId) -> {
            PayloadBudget.Fitted<T> fitted = PayloadBudget.requireWithinBudget(raw, maxPayloadBytes);
            return new Finished<>(fitted.result(), fitted.byteLength(), null, false);
        });
    }

    /** What {@link #instrumentedWith} needs to know about a finished response to audit and meter it. */
    private record Finished<R>(R result, long byteLength, Integer rowCount, boolean truncated) {}

    private <T, R> R instrumentedWith(
            String toolName,
            String schema,
            String fingerprint,
            Supplier<T> work,
            BiFunction<T, String, Finished<R>> finisher) {
        String correlationId = UUID.randomUUID().toString();
        long start = System.nanoTime();
        Span rootSpan = spanRecorder.startRoot("mcp.tool " + toolName);
        rootSpan.tag("tool", toolName);
        rootSpan.tag("correlationId", correlationId);
        try {
            if (schema != null) {
                try {
                    spanRecorder.inChildSpan(rootSpan, "scope.authorize", () -> schemaAllowlistValidator.validateSchema(schema));
                } catch (RuntimeException e) {
                    recordOutcome(
                            toolName, correlationId, PolicyDecisionReason.SCHEMA_NOT_ALLOWED, fingerprint, start, null, null, null);
                    throw new McpToolException(
                            ErrorCategory.SCOPE_DENIED, "Schema is not in the configured allowlist", null, correlationId);
                }
            }
            long db2Start = System.nanoTime();
            try {
                T raw = spanRecorder.inChildSpan(rootSpan, "db2.execute", work);
                metricsRecorder.recordDb2QueryDuration(toolName, millisSince(db2Start));
                Finished<R> finished = finisher.apply(raw, correlationId);
                metricsRecorder.recordResultBytes(toolName, finished.byteLength());
                recordOutcome(
                        toolName,
                        correlationId,
                        PolicyDecisionReason.ALLOWED,
                        fingerprint,
                        start,
                        finished.rowCount(),
                        finished.truncated(),
                        null);
                return finished.result();
            } catch (PayloadBudget.BudgetExceededException e) {
                metricsRecorder.recordDb2QueryDuration(toolName, millisSince(db2Start));
                recordOutcome(
                        toolName, correlationId, PolicyDecisionReason.PAYLOAD_LIMIT_EXCEEDED, fingerprint, start, null, null, null);
                throw new McpToolException(
                        ErrorCategory.RESULT_LIMIT_EXCEEDED,
                        "Result exceeds the configured maximum response size",
                        null,
                        correlationId);
            } catch (PayloadBudget.SizingException e) {
                // A response that cannot be serialized cannot be measured,
                // and an unmeasurable response must not be sent (#62): the
                // predecessor's `return 0` treated exactly this case as
                // costing nothing. INTERNAL_ERROR, not RESULT_LIMIT_EXCEEDED
                // - nothing is known about its size, only that the size is
                // unknowable, and reporting it as "too large" would invite
                // the operator to raise a limit that is not the problem.
                metricsRecorder.recordDb2QueryDuration(toolName, millisSince(db2Start));
                recordOutcome(
                        toolName, correlationId, PolicyDecisionReason.EXECUTION_FAILED, fingerprint, start, null, null, null);
                throw new McpToolException(
                        ErrorCategory.INTERNAL_ERROR, "Result could not be serialized", null, correlationId);
            } catch (NotFoundException e) {
                metricsRecorder.recordDb2QueryDuration(toolName, millisSince(db2Start));
                recordOutcome(
                        toolName, correlationId, PolicyDecisionReason.EXECUTION_FAILED, fingerprint, start, null, null, null);
                throw new McpToolException(ErrorCategory.DB2_OBJECT_NOT_FOUND, e.getMessage(), null, correlationId);
            } catch (DataAccessException e) {
                String sqlState = Db2Exceptions.extractSqlState(e);
                metricsRecorder.recordDb2QueryDuration(toolName, millisSince(db2Start));
                metricsRecorder.recordDb2Error(sqlState);
                recordOutcome(
                        toolName, correlationId, PolicyDecisionReason.EXECUTION_FAILED, fingerprint, start, null, null, sqlState);
                throw new McpToolException(
                        errorClassifier.classify(sqlState), toolName + " query failed", sqlState, correlationId);
            }
        } finally {
            rootSpan.end();
        }
    }

    /** Internal signal for "no such procedure" - see {@link #instrumented}. */
    private static final class NotFoundException extends RuntimeException {
        NotFoundException(String message) {
            super(message);
        }
    }

    private void recordOutcome(
            String toolName,
            String correlationId,
            PolicyDecisionReason reason,
            String fingerprint,
            long startNanos,
            Integer rowCount,
            Boolean truncated,
            String sqlState) {
        long durationMillis = millisSince(startNanos);

        AuditEvent event = reason == PolicyDecisionReason.ALLOWED
                ? AuditEvent.allowed(correlationId, toolName, fingerprint, durationMillis, rowCount, truncated)
                : reason == PolicyDecisionReason.EXECUTION_FAILED
                        ? AuditEvent.failed(correlationId, toolName, fingerprint, durationMillis, sqlState)
                        : AuditEvent.rejected(correlationId, toolName, reason, fingerprint, durationMillis);
        auditLogger.record(event);

        String outcome = reason == PolicyDecisionReason.ALLOWED
                ? "allowed"
                : reason == PolicyDecisionReason.EXECUTION_FAILED ? "failed" : "rejected";
        metricsRecorder.recordCall(toolName, outcome, durationMillis);
        metricsRecorder.recordPolicyDecision(capabilityProfile, reason);
        if (reason == PolicyDecisionReason.ALLOWED) {
            // rowCount is null for the two single-object results (describeProcedure,
            // getProcedureSource aren't Lists) - nothing meaningful to record as a
            // row count for those, so skip rather than unbox a null Integer.
            if (rowCount != null) {
                metricsRecorder.recordRows(toolName, rowCount);
            }
        } else if (reason != PolicyDecisionReason.EXECUTION_FAILED) {
            metricsRecorder.recordRejected(toolName, reason);
        }
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static String fingerprintOf(String label, String... parts) {
        String joined = Arrays.stream(parts)
                .map(p -> p == null ? "?" : p.toUpperCase(Locale.ROOT))
                .collect(Collectors.joining("."));
        return label + "[" + joined + "]";
    }

    static void requireIdentifier(String identifier) {
        if (identifier == null || !IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException("Invalid identifier: " + identifier);
        }
    }

    // SYSCAT.SCHEMATA.SCHEMANAME is a fixed-width CHAR(128) column in DB2; trim required.
    static SchemaInfo mapSchema(ResultSet rs, int rowNum) throws SQLException {
        return new SchemaInfo(rs.getString("SCHEMANAME").trim());
    }

    static TableInfo mapTable(ResultSet rs, int rowNum) throws SQLException {
        String type = switch (rs.getString("TYPE").trim()) {
            case "T" -> "TABLE";
            case "V" -> "VIEW";
            case String other -> other;
        };
        return new TableInfo(rs.getString("TABSCHEMA").trim(), rs.getString("TABNAME").trim(), type);
    }

    static ColumnInfo mapColumn(ResultSet rs, int rowNum) throws SQLException {
        return new ColumnInfo(
                rs.getString("COLNAME").trim(),
                rs.getString("TYPENAME").trim(),
                (Integer) rs.getObject("LENGTH"),
                (Integer) rs.getObject("SCALE"),
                "Y".equals(rs.getString("NULLS")),
                "Y".equals(rs.getString("IS_PK")),
                rs.getInt("COLNO"));
    }

    static TableDependencyInfo mapDependency(ResultSet rs, int rowNum) throws SQLException {
        return new TableDependencyInfo(
                rs.getString("CONSTNAME").trim(),
                rs.getString("DIRECTION"),
                rs.getString("FK_SCHEMA").trim(),
                rs.getString("FK_TABLE").trim(),
                rs.getString("FK_COLUMN").trim(),
                rs.getString("PK_SCHEMA").trim(),
                rs.getString("PK_TABLE").trim(),
                rs.getString("PK_COLUMN").trim());
    }

    static ProcedureInfo mapProcedure(ResultSet rs, int rowNum) throws SQLException {
        return new ProcedureInfo(
                rs.getString("PROCSCHEMA").trim(),
                rs.getString("PROCNAME").trim(),
                rs.getString("SPECIFICNAME").trim(),
                rs.getString("LANGUAGE").trim(),
                rs.getInt("PARM_COUNT"));
    }

    static ProcedureParameterInfo mapParameter(ResultSet rs, int rowNum) throws SQLException {
        return new ProcedureParameterInfo(
                rs.getString("PARMNAME").trim(),
                rs.getString("TYPENAME").trim(),
                rs.getString("PARM_MODE").trim(),
                rs.getInt("ORDINAL"));
    }

    static ProcedureSource mapSource(ResultSet rs, int maxCellBytes) throws SQLException {
        return new ProcedureSource(
                rs.getString("PROCSCHEMA").trim(),
                rs.getString("PROCNAME").trim(),
                rs.getString("SPECIFICNAME").trim(),
                boundedCell(rs.getString("TEXT"), maxCellBytes));
    }

    static ReferenceDataEntry mapRefData(ResultSet rs, int maxCellBytes) throws SQLException {
        return new ReferenceDataEntry(
                boundedCell(rs.getString("CODE"), maxCellBytes), boundedCell(rs.getString("DESCRIPTION"), maxCellBytes));
    }

    /**
     * Bounds a single caller-selected-data string cell to {@code
     * maxCellBytes} (P0-2, 2026-09-01 review) - {@code lookupRefData}'s
     * CODE/DESCRIPTION and {@code getProcedureSource}'s TEXT are the only
     * metadata-adapter output columns backed by arbitrary, unbounded table
     * or procedure-body content rather than a DB2 catalog identifier (whose
     * length DB2 itself already bounds); reuses {@link Db2ValueNormalizer}'s
     * own UTF-8-safe truncation rather than a second implementation of it.
     * {@code null} passes through unchanged - a {@code NULL} column value,
     * not a size to bound.
     */
    private static String boundedCell(String value, int maxCellBytes) {
        return value == null ? null : (String) Db2ValueNormalizer.normalize(value, maxCellBytes).value();
    }
}
