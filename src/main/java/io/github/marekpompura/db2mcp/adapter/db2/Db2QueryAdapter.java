package io.github.marekpompura.db2mcp.adapter.db2;

import io.github.marekpompura.db2mcp.adapter.observability.AuditLogger;
import io.github.marekpompura.db2mcp.adapter.observability.MetricsRecorder;
import io.github.marekpompura.db2mcp.adapter.observability.SpanRecorder;
import io.github.marekpompura.db2mcp.application.port.out.ExplainPort;
import io.github.marekpompura.db2mcp.application.port.out.QueryPort;
import io.github.marekpompura.db2mcp.configuration.ActiveStatementRegistry;
import io.github.marekpompura.db2mcp.domain.audit.AuditEvent;
import io.github.marekpompura.db2mcp.domain.audit.PolicyDecisionReason;
import io.github.marekpompura.db2mcp.domain.error.DatabaseErrorClassifier;
import io.github.marekpompura.db2mcp.domain.error.ErrorCategory;
import io.github.marekpompura.db2mcp.domain.error.McpToolException;
import io.github.marekpompura.db2mcp.domain.model.ExplainOperator;
import io.github.marekpompura.db2mcp.domain.model.ExplainPlanResult;
import io.github.marekpompura.db2mcp.domain.model.QueryColumn;
import io.github.marekpompura.db2mcp.domain.model.QueryResult;
import io.github.marekpompura.db2mcp.policy.capability.CapabilityProfile;
import io.github.marekpompura.db2mcp.policy.capability.CapabilityProperties;
import io.github.marekpompura.db2mcp.policy.sql.AdvancedQueryLimitsProperties;
import io.github.marekpompura.db2mcp.policy.sql.QueryLimitsProperties;
import io.github.marekpompura.db2mcp.policy.sql.ResolvedQueryLimits;
import io.github.marekpompura.db2mcp.policy.sql.SchemaAllowlistValidator;
import io.github.marekpompura.db2mcp.policy.sql.SqlComplexityValidator;
import io.github.marekpompura.db2mcp.policy.sql.SqlFingerprint;
import io.github.marekpompura.db2mcp.policy.sql.SqlStructuralValidator;
import io.micrometer.tracing.Span;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.StatementCallback;
import org.springframework.stereotype.Component;

/**
 * Guarded ad-hoc SQL execution. This is layer 3 of the read-only enforcement
 * in ADR 0002 (application guard); layers 1-2 are the DB2 user's grants and
 * Hikari's read-only connection setting, both outside this class's control.
 *
 * Also enforces the capability profile (ADR 0006) and structural SQL
 * validation plus resource limits (ADR 0007) at the adapter level, not just
 * by omitting the tool from registration in McpConfig - defense in depth
 * against any future path that might call this service directly. Every
 * call is recorded to the audit trail (ADR 0010), as metrics (ADR 0011),
 * and as a span tree (ADR 0012), success or rejection.
 *
 * <p>Also implements {@link ExplainPort} (ADR 0033) - the same physical
 * connection, capability profile, and SQL validators this class already
 * owns are exactly what explain-plan capture needs too, so it lives here
 * rather than in a second class that would duplicate all of that wiring.
 */
@Component
public class Db2QueryAdapter implements QueryPort, ExplainPort {

    private static final Pattern ALLOWED_START = Pattern.compile("^\\s*(SELECT|WITH)\\b", Pattern.CASE_INSENSITIVE);
    private static final String TOOL_NAME = "executeQuery";
    private static final String EXPLAIN_TOOL_NAME = "explainQuery";

    private final JdbcTemplate jdbcTemplate;
    private final CapabilityProfile capabilityProfile;
    private final SqlStructuralValidator structuralValidator;
    private final SchemaAllowlistValidator schemaAllowlistValidator;
    private final SqlComplexityValidator complexityValidator;
    private final SqlFingerprint sqlFingerprint;
    private final AuditLogger auditLogger;
    private final MetricsRecorder metricsRecorder;
    private final SpanRecorder spanRecorder;
    private final DatabaseErrorClassifier errorClassifier;
    private final ActiveStatementRegistry activeStatementRegistry;
    private final int maxRows;
    private final int maxCellBytes;
    private final int maxPayloadBytes;

    public Db2QueryAdapter(
            DataSource dataSource,
            CapabilityProperties capabilityProperties,
            SqlStructuralValidator structuralValidator,
            SchemaAllowlistValidator schemaAllowlistValidator,
            SqlComplexityValidator complexityValidator,
            QueryLimitsProperties queryLimitsProperties,
            AdvancedQueryLimitsProperties advancedQueryLimitsProperties,
            SqlFingerprint sqlFingerprint,
            AuditLogger auditLogger,
            MetricsRecorder metricsRecorder,
            SpanRecorder spanRecorder,
            DatabaseErrorClassifier errorClassifier,
            ActiveStatementRegistry activeStatementRegistry) {
        this.capabilityProfile = capabilityProperties.capabilityProfile();
        // ADR 0032: ADVANCED_QUERY enforces its own, independently configured
        // limits (AdvancedQueryLimitsProperties), not SAFE_QUERY's - resolved
        // once, here, so this adapter's enforcement and Db2CapabilityAdapter's
        // reported getCapabilities manifest can never disagree about which
        // limits are actually in effect.
        ResolvedQueryLimits resolvedLimits =
                ResolvedQueryLimits.of(capabilityProfile, queryLimitsProperties, advancedQueryLimitsProperties);
        // A dedicated JdbcTemplate, not the shared bean, so maxRows/queryTimeout are
        // fixed at construction and never mutate shared state on a call-by-call basis.
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.maxRows = resolvedLimits.maxRows();
        this.jdbcTemplate.setMaxRows(maxRows + 1); // +1 to detect truncation without a separate COUNT query
        this.jdbcTemplate.setQueryTimeout(resolvedLimits.timeoutSeconds());
        this.maxCellBytes = resolvedLimits.maxCellBytes();
        this.maxPayloadBytes = resolvedLimits.maxPayloadBytes();
        this.structuralValidator = structuralValidator;
        this.schemaAllowlistValidator = schemaAllowlistValidator;
        this.complexityValidator = complexityValidator;
        this.sqlFingerprint = sqlFingerprint;
        this.auditLogger = auditLogger;
        this.metricsRecorder = metricsRecorder;
        this.spanRecorder = spanRecorder;
        this.errorClassifier = errorClassifier;
        this.activeStatementRegistry = activeStatementRegistry;
    }

    @Override
    public QueryResult executeQuery(String sql) {
        String correlationId = UUID.randomUUID().toString();
        long start = System.nanoTime();
        String fingerprint = sqlFingerprint.of(sql);
        Span rootSpan = spanRecorder.startRoot("mcp.tool " + TOOL_NAME);
        rootSpan.tag("tool", TOOL_NAME);
        rootSpan.tag("correlationId", correlationId);

        try {
            try {
                spanRecorder.inChildSpan(
                        rootSpan, "capability.authorize", () -> capabilityProfile.requireQueryExecutionAllowed(TOOL_NAME));
            } catch (RuntimeException e) {
                recordOutcome(
                        TOOL_NAME, correlationId, PolicyDecisionReason.CAPABILITY_DISABLED, fingerprint, start, null, null, null);
                throw new McpToolException(ErrorCategory.CAPABILITY_DISABLED, e.getMessage(), null, correlationId);
            }

            try {
                spanRecorder.inChildSpan(rootSpan, "input.validate", () -> requireReadOnlyStatement(sql));
                spanRecorder.inChildSpan(rootSpan, "sql.validate", () -> structuralValidator.validate(sql));
            } catch (RuntimeException e) {
                recordOutcome(
                        TOOL_NAME, correlationId, PolicyDecisionReason.INVALID_STATEMENT, fingerprint, start, null, null, null);
                // P0-3 (2026-09-01 review): never forward a validator's own
                // exception message to the MCP client - SqlStructuralValidator
                // embeds JSQLParserException.getMessage(), parser-internal
                // diagnostic text this project's error-sanitization discipline
                // (ADR 0018/0020) doesn't allow across this boundary. A stable
                // generic message instead; e itself isn't chained either, same
                // reasoning as the DataAccessException handling below.
                throw new McpToolException(ErrorCategory.VALIDATION_FAILED, "SQL failed structural validation", null, correlationId);
            }

            try {
                spanRecorder.inChildSpan(rootSpan, "scope.authorize", () -> schemaAllowlistValidator.validate(sql));
            } catch (RuntimeException e) {
                recordOutcome(
                        TOOL_NAME, correlationId, PolicyDecisionReason.SCHEMA_NOT_ALLOWED, fingerprint, start, null, null, null);
                // P0-3: SchemaAllowlistValidator embeds the caller's own table
                // reference in its exception message - not forwarded either.
                throw new McpToolException(
                        ErrorCategory.SCOPE_DENIED, "Table reference is not in the configured allowlist", null, correlationId);
            }

            try {
                spanRecorder.inChildSpan(rootSpan, "sql.complexity", () -> complexityValidator.validate(sql));
            } catch (RuntimeException e) {
                recordOutcome(
                        TOOL_NAME, correlationId, PolicyDecisionReason.COMPLEXITY_EXCEEDED, fingerprint, start, null, null, null);
                // P0-3: SqlComplexityValidator's own parse-failure branch
                // embeds JSQLParserException.getMessage() the same way
                // SqlStructuralValidator's does - same treatment.
                throw new McpToolException(ErrorCategory.VALIDATION_FAILED, "SQL failed complexity validation", null, correlationId);
            }

            long db2Start = System.nanoTime();
            try {
                // StatementCallback, not the simpler jdbcTemplate.query(sql,
                // ResultSetExtractor) this used before issue #46: only a
                // StatementCallback hands back the live Statement itself, so
                // it can be registered with activeStatementRegistry and
                // cancelled from another thread (StdioLifecycleConfig, on
                // MCP-client disconnect) instead of running to completion or
                // its own timeout regardless of whether anyone is still
                // waiting for the response.
                QueryResult result = spanRecorder.inChildSpan(
                        rootSpan,
                        "db2.execute",
                        () -> jdbcTemplate.execute((StatementCallback<QueryResult>) stmt -> {
                            activeStatementRegistry.register(stmt);
                            try {
                                try (ResultSet rs = stmt.executeQuery(sql)) {
                                    ResultSetMetaData meta = rs.getMetaData();
                                    int columnCount = meta.getColumnCount();
                                    List<QueryColumn> columns = new ArrayList<>();
                                    for (int i = 1; i <= columnCount; i++) {
                                        columns.add(new QueryColumn(meta.getColumnLabel(i), meta.getColumnTypeName(i)));
                                    }
                                    List<List<Object>> rows = new ArrayList<>();
                                    long contentBytes = 0;
                                    while (rs.next()) {
                                        List<Object> row = new ArrayList<>(columnCount);
                                        for (int i = 1; i <= columnCount; i++) {
                                            Db2ValueNormalizer.Normalized cell =
                                                    Db2ValueNormalizer.normalize(rs.getObject(i), maxCellBytes);
                                            row.add(cell.value());
                                        }
                                        rows.add(row);
                                        // A cheap running total purely to stop reading a
                                        // result set that has already outgrown any response
                                        // we could send - it is NOT the bound itself. The
                                        // real bound is applied below against the fully
                                        // assembled QueryResult, envelope included (#62,
                                        // 2026-09-09 re-review, RR-P0-1); this loop only
                                        // decides when to stop pulling rows into memory.
                                        // Stopping here needs no truncation flag of its own:
                                        // row content alone already exceeding the budget
                                        // means the assembled response must exceed it too,
                                        // so the fit below is guaranteed to trim and report
                                        // truncated regardless.
                                        contentBytes += PayloadBudget.byteLength(row);
                                        if (contentBytes > maxPayloadBytes || rows.size() > maxRows) {
                                            break;
                                        }
                                    }
                                    boolean rowCapTruncated = rows.size() > maxRows;
                                    if (rowCapTruncated) {
                                        rows = rows.subList(0, maxRows);
                                    }
                                    // The complete response is what gets measured and what
                                    // gets returned - column descriptors, correlation ID,
                                    // wrapper keys, separators and all. Summing row content
                                    // alone (the previous model) could admit a response
                                    // whose serialized form was over budget while every
                                    // individual row was inside it.
                                    PayloadBudget.Fitted<QueryResult> fitted = PayloadBudget.fit(
                                            rows,
                                            rowCapTruncated,
                                            maxPayloadBytes,
                                            (kept, truncated) -> new QueryResult(
                                                    columns, kept, kept.size(), truncated, correlationId));
                                    metricsRecorder.recordResultBytes(TOOL_NAME, fitted.byteLength());
                                    return fitted.result();
                                } catch (SQLException e) {
                                    // Only ActiveStatementRegistry's own cancelAll() ever
                                    // calls Statement.cancel() on this statement - if it
                                    // did, this SQLException is that cancellation
                                    // surfacing, not a coincidentally-identical driver
                                    // error. Checked here, before deregistering below,
                                    // since deregister() clears the cancelled flag too.
                                    if (activeStatementRegistry.wasCancelled(stmt)) {
                                        throw new QueryCancelledException(e);
                                    }
                                    throw e;
                                }
                            } finally {
                                activeStatementRegistry.deregister(stmt);
                            }
                        }));
                metricsRecorder.recordDb2QueryDuration(TOOL_NAME, millisSince(db2Start));
                recordOutcome(
                        TOOL_NAME,
                        correlationId,
                        PolicyDecisionReason.ALLOWED,
                        fingerprint,
                        start,
                        result.rowCount(),
                        result.truncated(),
                        null);
                return result;
            } catch (PayloadBudget.BudgetExceededException e) {
                // Not even a zero-row response fits - for executeQuery that
                // means the column descriptors alone are over budget (a very
                // wide SELECT against a very small maxPayloadBytes). Refused
                // rather than sent over the bound, which is the only answer
                // that keeps the bound hard (#62, RR-P0-1).
                metricsRecorder.recordDb2QueryDuration(TOOL_NAME, millisSince(db2Start));
                recordOutcome(
                        TOOL_NAME,
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
                metricsRecorder.recordDb2QueryDuration(TOOL_NAME, millisSince(db2Start));
                recordOutcome(
                        TOOL_NAME,
                        correlationId,
                        PolicyDecisionReason.EXECUTION_FAILED,
                        fingerprint,
                        start,
                        null,
                        null,
                        null);
                throw new McpToolException(
                        ErrorCategory.INTERNAL_ERROR, "Result could not be serialized", null, correlationId);
            } catch (QueryCancelledException e) {
                // Issue #46: the MCP client had already disconnected and
                // this query was proactively cancelled rather than left
                // running - there is no one left to receive this tool's
                // response, so the thrown exception's category/message
                // matter only for internal consistency, not client
                // visibility. The audit/metrics distinction is what
                // actually matters here.
                String sqlState = e.sqlState();
                metricsRecorder.recordDb2QueryDuration(TOOL_NAME, millisSince(db2Start));
                recordOutcome(
                        TOOL_NAME, correlationId, PolicyDecisionReason.CANCELLED, fingerprint, start, null, null, sqlState);
                throw new McpToolException(
                        ErrorCategory.QUERY_TIMEOUT, "Query was cancelled", sqlState, correlationId);
            } catch (DataAccessException e) {
                // Never propagate the raw driver/DB2 exception to the MCP client - it can
                // contain internal object names, hostnames, or a full stack trace. Only
                // SQLSTATE (a generic standard code, not sensitive) is surfaced. The cause
                // is deliberately not chained onto the thrown exception either, so nothing
                // richer can leak through a later serialization path.
                String sqlState = Db2Exceptions.extractSqlState(e);
                metricsRecorder.recordDb2QueryDuration(TOOL_NAME, millisSince(db2Start));
                metricsRecorder.recordDb2Error(sqlState);
                recordOutcome(
                        TOOL_NAME,
                        correlationId,
                        PolicyDecisionReason.EXECUTION_FAILED,
                        fingerprint,
                        start,
                        null,
                        null,
                        sqlState);
                throw new McpToolException(
                        errorClassifier.classify(sqlState), "Query execution failed", sqlState, correlationId);
            }
        } finally {
            rootSpan.end();
        }
    }

    /**
     * Internal signal only - thrown from inside the {@link StatementCallback}
     * above exactly when {@link ActiveStatementRegistry#wasCancelled} confirms
     * this statement's failure is this server's own explicit cancellation
     * (issue #46), not a coincidentally-identical driver error. Deliberately
     * a plain {@link RuntimeException}, not a {@link DataAccessException}:
     * {@link JdbcTemplate#execute(StatementCallback)} only wraps checked
     * {@link SQLException}s thrown from the callback - an unchecked exception
     * propagates through it unchanged, straight to the dedicated catch clause
     * above, without needing a second SQLSTATE-based guess at what happened.
     */
    private static final class QueryCancelledException extends RuntimeException {
        private final String sqlState;

        QueryCancelledException(SQLException cause) {
            super(cause);
            this.sqlState = cause.getSQLState();
        }

        String sqlState() {
            return sqlState;
        }
    }

    /**
     * DB2 EXPLAIN-plan capture (ADR 0033) - {@code ADVANCED_QUERY}-only,
     * gated more narrowly than {@link #executeQuery} via {@link
     * CapabilityProfile#requireExplainPlanAllowed}. The statement being
     * explained still passes through the identical structural/schema gates
     * {@code executeQuery} enforces (master brief section 6.3: "must never
     * weaken database-account least privilege" - explaining a statement
     * DB2 wouldn't otherwise be allowed to run is still a policy exception,
     * not a harmless no-op).
     *
     * <p><b>The explained statement's own business logic never runs</b> -
     * {@code EXPLAIN PLAN FOR} never executes the caller's SQL. But {@code
     * EXPLAIN PLAN} is not read-only itself: it is a genuine, documented
     * exception to this project's read-only guarantee (README.md, ADR
     * 0002, {@code docs/security/threat-model.md}) - it physically writes
     * plan metadata into DB2's own
     * EXPLAIN_STATEMENT/EXPLAIN_OPERATOR catalog tables (read back
     * afterward by a second query), the same two tables every call
     * targets, never the caller's own schema data. A prior version of this
     * comment said "[n]othing is executed for real," which was accurate
     * about the business logic but conflated that with this catalog write
     * - flagged by the 2026-09-01 master-brief gap review (P1-4, issue
     * #41) and corrected here.
     */
    @Override
    public ExplainPlanResult explainQuery(String sql) {
        String correlationId = UUID.randomUUID().toString();
        long start = System.nanoTime();
        String fingerprint = sqlFingerprint.of(sql);
        Span rootSpan = spanRecorder.startRoot("mcp.tool " + EXPLAIN_TOOL_NAME);
        rootSpan.tag("tool", EXPLAIN_TOOL_NAME);
        rootSpan.tag("correlationId", correlationId);

        try {
            try {
                spanRecorder.inChildSpan(
                        rootSpan,
                        "capability.authorize",
                        () -> capabilityProfile.requireExplainPlanAllowed(EXPLAIN_TOOL_NAME));
            } catch (RuntimeException e) {
                recordOutcome(
                        EXPLAIN_TOOL_NAME,
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
                spanRecorder.inChildSpan(rootSpan, "input.validate", () -> requireReadOnlyStatement(sql));
                spanRecorder.inChildSpan(rootSpan, "sql.validate", () -> structuralValidator.validate(sql));
            } catch (RuntimeException e) {
                recordOutcome(
                        EXPLAIN_TOOL_NAME,
                        correlationId,
                        PolicyDecisionReason.INVALID_STATEMENT,
                        fingerprint,
                        start,
                        null,
                        null,
                        null);
                // P0-3 (2026-09-01 review): see executeQuery's identical catch
                // above for why this never forwards e.getMessage().
                throw new McpToolException(ErrorCategory.VALIDATION_FAILED, "SQL failed structural validation", null, correlationId);
            }

            try {
                spanRecorder.inChildSpan(rootSpan, "scope.authorize", () -> schemaAllowlistValidator.validate(sql));
            } catch (RuntimeException e) {
                recordOutcome(
                        EXPLAIN_TOOL_NAME,
                        correlationId,
                        PolicyDecisionReason.SCHEMA_NOT_ALLOWED,
                        fingerprint,
                        start,
                        null,
                        null,
                        null);
                throw new McpToolException(
                        ErrorCategory.SCOPE_DENIED, "Table reference is not in the configured allowlist", null, correlationId);
            }

            try {
                spanRecorder.inChildSpan(rootSpan, "sql.complexity", () -> complexityValidator.validate(sql));
            } catch (RuntimeException e) {
                recordOutcome(
                        EXPLAIN_TOOL_NAME,
                        correlationId,
                        PolicyDecisionReason.COMPLEXITY_EXCEEDED,
                        fingerprint,
                        start,
                        null,
                        null,
                        null);
                throw new McpToolException(ErrorCategory.VALIDATION_FAILED, "SQL failed complexity validation", null, correlationId);
            }

            long db2Start = System.nanoTime();
            try {
                ExplainPlanResult raw = spanRecorder.inChildSpan(
                        rootSpan, "db2.explain", () -> captureAndReadPlan(sql, correlationId));
                // The whole plan result, not just its operators (#62,
                // 2026-09-09 re-review, RR-P0-1) - statementText is the
                // caller's own SQL echoed back and can be far larger than
                // the operator list it was excluded alongside.
                PayloadBudget.Fitted<ExplainPlanResult> fitted = PayloadBudget.fit(
                        raw.operators(),
                        raw.truncated(),
                        maxPayloadBytes,
                        (kept, truncated) ->
                                new ExplainPlanResult(raw.statementText(), kept, truncated, correlationId));
                metricsRecorder.recordResultBytes(EXPLAIN_TOOL_NAME, fitted.byteLength());
                metricsRecorder.recordDb2QueryDuration(EXPLAIN_TOOL_NAME, millisSince(db2Start));
                recordOutcome(
                        EXPLAIN_TOOL_NAME,
                        correlationId,
                        PolicyDecisionReason.ALLOWED,
                        fingerprint,
                        start,
                        fitted.result().operators().size(),
                        fitted.truncated(),
                        null);
                return fitted.result();
            } catch (PayloadBudget.BudgetExceededException e) {
                // Even a zero-operator plan is over budget - which for
                // explainQuery means the echoed statementText alone does not
                // fit. Refused rather than sent over the configured bound.
                metricsRecorder.recordDb2QueryDuration(EXPLAIN_TOOL_NAME, millisSince(db2Start));
                recordOutcome(
                        EXPLAIN_TOOL_NAME,
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
                metricsRecorder.recordDb2QueryDuration(EXPLAIN_TOOL_NAME, millisSince(db2Start));
                recordOutcome(
                        EXPLAIN_TOOL_NAME,
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
                // Same sanitization policy as executeQuery - only SQLSTATE ever
                // surfaces. A DB2 instance without EXPLAIN.DDL's tables provisioned
                // (a live-verification prerequisite, ADR 0033) fails here too,
                // through this same generic path.
                String sqlState = Db2Exceptions.extractSqlState(e);
                metricsRecorder.recordDb2QueryDuration(EXPLAIN_TOOL_NAME, millisSince(db2Start));
                metricsRecorder.recordDb2Error(sqlState);
                recordOutcome(
                        EXPLAIN_TOOL_NAME,
                        correlationId,
                        PolicyDecisionReason.EXECUTION_FAILED,
                        fingerprint,
                        start,
                        null,
                        null,
                        sqlState);
                throw new McpToolException(
                        errorClassifier.classify(sqlState), "Explain plan capture failed", sqlState, correlationId);
            }
        } finally {
            rootSpan.end();
        }
    }

    /**
     * Issues {@code EXPLAIN PLAN SET QUERYTAG = '<tag>' FOR <sql>} (populates
     * DB2's explain tables for this statement only - no bind parameter
     * needed for {@code sql} itself, since it has already passed the
     * identical single-statement structural/schema gates {@link
     * #executeQuery} enforces before this method is ever reached, the same
     * trust boundary {@code executeQuery} itself already relies on for its
     * own literal-text {@code jdbcTemplate.query(sql, ...)} call), then
     * reads back only the operator rows tagged with this exact call's
     * {@code QUERYTAG}.
     *
     * <p><b>Fixes a live-DB2-verified correlation race (issue #28, found
     * during ADR 0033's live-DB2 verification):</b> an earlier version of
     * this method correlated "the plan just captured" via {@code WHERE
     * EXPLAIN_TIME = (SELECT MAX(EXPLAIN_TIME) FROM EXPLAIN_STATEMENT)},
     * which is a database-wide, not per-call, condition - two concurrent
     * {@code explainQuery} calls could and did (confirmed against real
     * DB2) return each other's operator trees, silently mislabeled under
     * the caller's own correct {@code statementText}. {@code EXPLAIN PLAN}'s
     * own {@code SET QUERYTAG} clause exists exactly for this: a
     * caller-supplied identifier written into {@code EXPLAIN_STATEMENT}
     * alongside the captured plan, letting the read-back join deterministically
     * to only this call's own rows instead of guessing from a timestamp.
     * {@code queryTag} is derived from {@code correlationId} (a
     * server-generated {@link UUID}, hex digits and dashes only) with
     * dashes stripped and truncated to fit {@code QUERYTAG}'s {@code
     * CHAR(20)} column - safe to embed directly since it is never
     * user-controlled input, the same trust boundary {@code sql} itself
     * doesn't get (hence the structural/schema validation gates upstream).
     */
    private ExplainPlanResult captureAndReadPlan(String sql, String correlationId) {
        String queryTag = correlationId.replace("-", "").substring(0, 20);
        jdbcTemplate.execute("EXPLAIN PLAN SET QUERYTAG = '" + queryTag + "' FOR " + sql);

        List<ExplainOperator> operators = jdbcTemplate.query(
                "SELECT eo.OPERATOR_ID, eo.OPERATOR_TYPE, eo.TOTAL_COST FROM EXPLAIN_OPERATOR eo "
                        + "JOIN EXPLAIN_STATEMENT es "
                        + "ON eo.EXPLAIN_REQUESTER = es.EXPLAIN_REQUESTER "
                        + "AND eo.EXPLAIN_TIME = es.EXPLAIN_TIME "
                        + "AND eo.SOURCE_NAME = es.SOURCE_NAME "
                        + "AND eo.SOURCE_SCHEMA = es.SOURCE_SCHEMA "
                        + "AND eo.SOURCE_VERSION = es.SOURCE_VERSION "
                        + "AND eo.EXPLAIN_LEVEL = es.EXPLAIN_LEVEL "
                        + "AND eo.STMTNO = es.STMTNO "
                        + "AND eo.SECTNO = es.SECTNO "
                        + "WHERE es.QUERYTAG = '" + queryTag + "' "
                        + "ORDER BY eo.OPERATOR_ID FETCH FIRST " + (maxRows + 1) + " ROWS ONLY",
                (rs, rowNum) -> new ExplainOperator(
                        rs.getInt("OPERATOR_ID"), rs.getString("OPERATOR_TYPE"), rs.getDouble("TOTAL_COST")));

        return truncateOperators(sql, operators, maxRows, correlationId);
    }

    /**
     * Pulled out as its own pure function (no JDBC involved) specifically
     * so this truncation logic - the one piece of {@code explainQuery} that
     * is genuinely this project's own code, not DB2 behavior - can be unit
     * tested directly, independent of the live-DB2-only parts above it.
     */
    static ExplainPlanResult truncateOperators(
            String sql, List<ExplainOperator> operators, int maxOperators, String correlationId) {
        boolean truncated = operators.size() > maxOperators;
        List<ExplainOperator> bounded = truncated ? operators.subList(0, maxOperators) : operators;
        return new ExplainPlanResult(sql, bounded, truncated, correlationId);
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

        // Switch expression (issue #46 added the CANCELLED branch) - matches
        // Db2SqlStateClassifier's own established style for this kind of
        // reason-to-outcome mapping, replacing what had been a nested
        // ternary chain before a third real branch made that harder to read
        // than a switch.
        AuditEvent event = switch (reason) {
            case ALLOWED -> AuditEvent.allowed(correlationId, toolName, fingerprint, durationMillis, rowCount, truncated);
            case EXECUTION_FAILED -> AuditEvent.failed(correlationId, toolName, fingerprint, durationMillis, sqlState);
            // Issue #46: a query this server itself cancelled because its
            // MCP client disconnected - distinct from EXECUTION_FAILED so
            // an operator isn't left conflating the two.
            case CANCELLED -> AuditEvent.cancelled(correlationId, toolName, fingerprint, durationMillis, sqlState);
            default -> AuditEvent.rejected(correlationId, toolName, reason, fingerprint, durationMillis);
        };
        auditLogger.record(event);

        String outcome = switch (reason) {
            case ALLOWED -> "allowed";
            case EXECUTION_FAILED -> "failed";
            case CANCELLED -> "cancelled";
            default -> "rejected";
        };
        metricsRecorder.recordCall(toolName, outcome, durationMillis);
        metricsRecorder.recordPolicyDecision(capabilityProfile, reason);
        if (reason == PolicyDecisionReason.ALLOWED) {
            metricsRecorder.recordRows(toolName, rowCount);
        } else if (reason != PolicyDecisionReason.EXECUTION_FAILED && reason != PolicyDecisionReason.CANCELLED) {
            metricsRecorder.recordRejected(toolName, reason);
        }
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    static void requireReadOnlyStatement(String sql) {
        if (sql == null || !ALLOWED_START.matcher(sql).find()) {
            throw new IllegalArgumentException("Only SELECT or WITH statements are allowed");
        }
        // A prefix check alone permits "SELECT 1; DROP TABLE ...". No multi-statement
        // SQL is allowed through this tool - a single trailing semicolon isn't needed
        // for JDBC execution either, so any semicolon at all is rejected. Kept as a
        // cheap first-pass filter in addition to SqlStructuralValidator, not replaced
        // by it - defense in depth, same reasoning as ADR 0002.
        if (sql.indexOf(';') >= 0) {
            throw new IllegalArgumentException("Multiple statements are not allowed");
        }
    }
}
