package io.github.marekpompura.db2mcp.adapter.db2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.marekpompura.db2mcp.adapter.observability.AuditLogger;
import io.github.marekpompura.db2mcp.adapter.observability.MetricsRecorder;
import io.github.marekpompura.db2mcp.adapter.observability.SpanRecorder;
import io.github.marekpompura.db2mcp.configuration.ActiveStatementRegistry;
import io.github.marekpompura.db2mcp.domain.error.ErrorCategory;
import io.github.marekpompura.db2mcp.domain.error.McpToolException;
import io.github.marekpompura.db2mcp.domain.model.QueryResult;
import io.github.marekpompura.db2mcp.policy.capability.CapabilityProfile;
import io.github.marekpompura.db2mcp.policy.capability.CapabilityProperties;
import io.github.marekpompura.db2mcp.policy.sql.AdvancedQueryComplexityLimitsProperties;
import io.github.marekpompura.db2mcp.policy.sql.AdvancedQueryLimitsProperties;
import io.github.marekpompura.db2mcp.policy.sql.QueryComplexityLimitsProperties;
import io.github.marekpompura.db2mcp.policy.sql.QueryLimitsProperties;
import io.github.marekpompura.db2mcp.policy.sql.QueryPolicyProperties;
import io.github.marekpompura.db2mcp.policy.sql.SchemaAllowlistValidator;
import io.github.marekpompura.db2mcp.policy.sql.SqlComplexityValidator;
import io.github.marekpompura.db2mcp.policy.sql.SqlFingerprint;
import io.github.marekpompura.db2mcp.policy.sql.SqlStructuralValidator;
import io.github.marekpompura.db2mcp.testsupport.ItsmH2Database;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.test.simple.SimpleSpan;
import io.micrometer.tracing.test.simple.SimpleTracer;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Tier 1 integration coverage (ADR 0009): executeQuery against a real H2
 * connection (MODE=DB2), exercising the full guard chain and JDBC round trip
 * - not mocks. H2-verified, not Db2-verified; see ADR 0009 for that
 * distinction and why it matters.
 */
class Db2QueryAdapterIntegrationTest {

    private static final QueryLimitsProperties DEFAULT_LIMITS = new QueryLimitsProperties(1000, 30, 16384, 1048576);
    private static final AdvancedQueryLimitsProperties DEFAULT_ADVANCED_LIMITS =
            new AdvancedQueryLimitsProperties(5000, 120, 65536, 4194304);
    // Generous - these tests aren't exercising complexity limits, so the new
    // gate (ADR 0034) must not incidentally reject any of their SQL.
    private static final SqlComplexityValidator PERMISSIVE_COMPLEXITY_VALIDATOR = new SqlComplexityValidator(
            new CapabilityProperties(CapabilityProfile.SAFE_QUERY),
            new QueryComplexityLimitsProperties(1000, 1000),
            new AdvancedQueryComplexityLimitsProperties(1000, 1000));

    private static Db2QueryAdapter newAdapter(DataSource dataSource) {
        return newAdapter(dataSource, DEFAULT_LIMITS, new SimpleMeterRegistry(), new SimpleTracer());
    }

    private static Db2QueryAdapter newAdapter(DataSource dataSource, QueryLimitsProperties limits) {
        return newAdapter(dataSource, limits, new SimpleMeterRegistry(), new SimpleTracer());
    }

    private static Db2QueryAdapter newAdapter(DataSource dataSource, QueryLimitsProperties limits, MeterRegistry registry) {
        return newAdapter(dataSource, limits, registry, new SimpleTracer());
    }

    private static Db2QueryAdapter newAdapter(
            DataSource dataSource, QueryLimitsProperties limits, MeterRegistry registry, SimpleTracer tracer) {
        return newAdapter(dataSource, limits, registry, tracer, new ActiveStatementRegistry());
    }

    private static Db2QueryAdapter newAdapter(
            DataSource dataSource,
            QueryLimitsProperties limits,
            MeterRegistry registry,
            SimpleTracer tracer,
            ActiveStatementRegistry activeStatementRegistry) {
        return new Db2QueryAdapter(
                dataSource,
                new CapabilityProperties(CapabilityProfile.SAFE_QUERY),
                new SqlStructuralValidator(),
                new SchemaAllowlistValidator(new QueryPolicyProperties(List.of("ITSM"))),
                PERMISSIVE_COMPLEXITY_VALIDATOR,
                limits,
                DEFAULT_ADVANCED_LIMITS,
                new SqlFingerprint(),
                new AuditLogger(),
                new MetricsRecorder(registry),
                new SpanRecorder(tracer),
                new Db2SqlStateClassifier(),
                activeStatementRegistry);
    }

    @Test
    void executeQuery_runsRealJoinAgainstH2AndMapsResults() {
        DataSource dataSource = ItsmH2Database.create("query_join");
        Db2QueryAdapter adapter = newAdapter(dataSource);

        QueryResult result = adapter.executeQuery(
                "SELECT t.TITLE, c.LAST_NAME FROM ITSM.TICKET t "
                        + "JOIN ITSM.CUSTOMER c ON c.CUSTOMER_ID = t.CUSTOMER_ID "
                        + "ORDER BY t.TICKET_ID");

        assertThat(result.columns()).extracting("name").containsExactly("TITLE", "LAST_NAME");
        assertThat(result.rows()).hasSize(2);
        assertThat(result.rows().get(0)).containsExactly("Cannot connect to VPN", "Schmidt");
        assertThat(result.rowCount()).isEqualTo(2);
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void executeQuery_truncatesAtRowCapAgainstRealResultSet() {
        DataSource dataSource = ItsmH2Database.create("query_truncation");
        Db2QueryAdapter adapter = newAdapter(dataSource);

        QueryResult result = adapter.executeQuery("SELECT N FROM ITSM.BULK_ROWS ORDER BY N");

        assertThat(result.rowCount()).isEqualTo(1000);
        assertThat(result.rows()).hasSize(1000);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void executeQuery_rejectsRealTableOutsideAllowedSchema() {
        DataSource dataSource = ItsmH2Database.create("query_allowlist");
        Db2QueryAdapter adapter = newAdapter(dataSource);

        // OTHER_SCHEMA.SECRET genuinely exists and is genuinely queryable in this
        // H2 instance - this proves the allowlist blocks it before execution,
        // not merely that a fictitious name would be rejected.
        assertThatThrownBy(() -> adapter.executeQuery("SELECT * FROM OTHER_SCHEMA.SECRET"))
                .isInstanceOf(McpToolException.class)
                .extracting(t -> ((McpToolException) t).category())
                .isEqualTo(ErrorCategory.SCOPE_DENIED);
    }

    // Adversarial catalogue (docs/security/adversarial-test-cases.md): query timeout,
    // large cross join. A 1-second configured timeout against a genuinely expensive
    // self-cross-join (1001^4 rows) of an allowed, schema-qualified table - passes
    // every guard layer and is only stopped by the timeout, not policy rejection.
    @Test
    void executeQuery_timesOutOnExpensiveCrossJoin() {
        // Deliberately NOT a comma-join/CROSS JOIN, and NOT a structurally
        // tautological ON clause either (ADR 0034/ADR 0035's
        // SqlComplexityValidator now rejects both of those, before this
        // query would ever reach DB2 at all - see SqlComplexityValidatorTest
        // for that coverage). "ON a.N > 0" is a genuine (if fully
        // non-selective, since N is always > 0) comparison against a
        // literal, not a structural tautology like "a.N = a.N" - it passes
        // the complexity gate while remaining exactly as expensive, so the
        // JDBC-level timeout mechanism (ADR 0007) is still independently
        // exercised here.
        DataSource dataSource = ItsmH2Database.create("query_timeout");
        Db2QueryAdapter adapter = newAdapter(dataSource, new QueryLimitsProperties(1000, 1, 16384, 1048576));

        assertThatThrownBy(() -> adapter.executeQuery(
                        "SELECT COUNT(*) FROM ITSM.BULK_ROWS a "
                                + "JOIN ITSM.BULK_ROWS b ON a.N > 0 "
                                + "JOIN ITSM.BULK_ROWS c ON b.N > 0 "
                                + "JOIN ITSM.BULK_ROWS d ON c.N > 0"))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("Query execution failed")
                .extracting(t -> ((McpToolException) t).category())
                // 57014 is the real SQLSTATE H2 reports for a JDBC statement timeout -
                // Db2SqlStateClassifier classifies it as QUERY_TIMEOUT, not the generic
                // DB2_EXECUTION_FAILED fallback (ADR 0018).
                .isEqualTo(ErrorCategory.QUERY_TIMEOUT);
    }

    // Issue #46 (2026-09-01 master-brief gap review, P2-2): a real,
    // mid-flight ActiveStatementRegistry.cancelAll() call against a real H2
    // connection - not a mocked Statement, unlike ActiveStatementRegistryTest's
    // own unit coverage - proving the whole path this project actually ships
    // (Db2QueryAdapter's StatementCallback registers the live Statement,
    // cancelAll() calls Statement.cancel() on it, H2 throws mid-execution,
    // and Db2QueryAdapter correctly attributes that failure to CANCELLED,
    // not EXECUTION_FAILED) genuinely works end to end.
    @Test
    void executeQuery_cancelledMidFlightThrowsQueryTimeoutAndRecordsCancelledInAudit() throws Exception {
        DataSource dataSource = ItsmH2Database.create("query_cancel");
        io.github.marekpompura.db2mcp.configuration.ActiveStatementRegistry activeStatementRegistry =
                new io.github.marekpompura.db2mcp.configuration.ActiveStatementRegistry();
        // A deliberately long timeout (120s) - if cancellation somehow
        // didn't work, this test would hang/fail slowly on a real timeout
        // instead of accidentally passing for the wrong reason.
        Db2QueryAdapter adapter = newAdapter(
                dataSource,
                new QueryLimitsProperties(1000, 120, 16384, 1048576),
                new SimpleMeterRegistry(),
                new SimpleTracer(),
                activeStatementRegistry);
        ListAppender<ILoggingEvent> appender = attachAuditListAppender();

        java.util.concurrent.CompletableFuture<Void> queryFuture = java.util.concurrent.CompletableFuture.runAsync(() -> assertThatThrownBy(
                        () -> adapter.executeQuery(
                                "SELECT COUNT(*) FROM ITSM.BULK_ROWS a "
                                        + "JOIN ITSM.BULK_ROWS b ON a.N > 0 "
                                        + "JOIN ITSM.BULK_ROWS c ON b.N > 0 "
                                        + "JOIN ITSM.BULK_ROWS d ON c.N > 0"))
                .isInstanceOf(McpToolException.class)
                .extracting(t -> ((McpToolException) t).category())
                .isEqualTo(ErrorCategory.QUERY_TIMEOUT));

        // Wait for the query to actually register itself as running before
        // cancelling - cancelling before it starts would just prevent it
        // from ever executing, proving nothing about mid-flight cancellation
        // specifically.
        org.awaitility.Awaitility.await()
                .atMost(java.time.Duration.ofSeconds(5))
                .until(() -> activeStatementRegistry.activeCount() > 0);
        int cancelledCount = activeStatementRegistry.cancelAll();
        assertThat(cancelledCount).isEqualTo(1);

        // Bounded well under the 120s configured timeout - if this assertion
        // ever times out, cancellation silently stopped working and the
        // query ran to its full JDBC timeout instead.
        queryFuture.get(10, java.util.concurrent.TimeUnit.SECONDS);

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("decision=CANCELLED");
    }

    // ADR 0035: end-to-end proof (real H2, not just SqlComplexityValidatorTest's
    // unit-level coverage) that a tautological ON clause is rejected before
    // ever reaching DB2 - even against PERMISSIVE_COMPLEXITY_VALIDATOR's
    // generous join/nesting limits, since this rejection is unconditional,
    // not limit-based.
    @Test
    void executeQuery_rejectsTautologicalOnClause() {
        DataSource dataSource = ItsmH2Database.create("query_tautological_on");
        Db2QueryAdapter adapter = newAdapter(dataSource);

        assertThatThrownBy(() ->
                        adapter.executeQuery("SELECT * FROM ITSM.TICKET t JOIN ITSM.CUSTOMER c ON 1=1"))
                .isInstanceOf(McpToolException.class)
                .extracting(t -> ((McpToolException) t).category())
                .isEqualTo(ErrorCategory.VALIDATION_FAILED);
    }

    // P0-3 (2026-09-01 master-brief gap review): a malformed-SQL parse
    // failure previously forwarded JSqlParser's own exception message -
    // which can echo back fragments of the caller's own SQL text and
    // parser-internal diagnostic detail - straight into the client-visible
    // McpToolException. Confirmed to fail against the pre-fix code (the raw
    // JSQLParserException message contains the sentinel token verbatim) and
    // pass against the fix.
    @Test
    void executeQuery_parseFailureMessageNeverContainsTheRawSqlOrParserDetail() {
        DataSource dataSource = ItsmH2Database.create("parse_failure_sanitization");
        Db2QueryAdapter adapter = newAdapter(dataSource);
        String sentinel = "SENTINEL_TOKEN_NEVER_LEAKED";

        assertThatThrownBy(() -> adapter.executeQuery("SELECT " + sentinel + " FROM WHERE GARBAGE ((("))
                .isInstanceOf(McpToolException.class)
                .hasMessageNotContaining(sentinel)
                .hasMessageNotContaining("net.sf.jsqlparser")
                .extracting(t -> ((McpToolException) t).category())
                .isEqualTo(ErrorCategory.VALIDATION_FAILED);
    }

    // P0-3: SchemaAllowlistValidator's own exception embeds the caller's raw
    // table reference - previously forwarded verbatim. A caller probing for
    // internal schema/table names (real or guessed) must not get them
    // echoed back through the rejection message itself.
    @Test
    void executeQuery_schemaRejectionMessageNeverContainsTheAttemptedTableReference() {
        DataSource dataSource = ItsmH2Database.create("schema_rejection_sanitization");
        Db2QueryAdapter adapter = newAdapter(dataSource);

        assertThatThrownBy(() -> adapter.executeQuery("SELECT * FROM SOME_SECRET_INTERNAL_SCHEMA.SOME_SECRET_TABLE"))
                .isInstanceOf(McpToolException.class)
                .hasMessageNotContaining("SOME_SECRET_INTERNAL_SCHEMA")
                .hasMessageNotContaining("SOME_SECRET_TABLE")
                .extracting(t -> ((McpToolException) t).category())
                .isEqualTo(ErrorCategory.SCOPE_DENIED);
    }

    // Adversarial catalogue: sensitive value leakage through errors. A real H2
    // execution error (unknown column) must not leak the raw driver message,
    // the column name, or any H2/JDBC internals back to the caller.
    @Test
    void executeQuery_sanitizesRealDatabaseErrors() {
        DataSource dataSource = ItsmH2Database.create("query_error_sanitization");
        Db2QueryAdapter adapter = newAdapter(dataSource);

        assertThatThrownBy(() -> adapter.executeQuery("SELECT NONEXISTENT_COLUMN FROM ITSM.TICKET"))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("Query execution failed")
                .hasMessageNotContaining("NONEXISTENT_COLUMN")
                .hasMessageNotContaining("org.h2")
                .extracting(t -> ((McpToolException) t).category())
                // An unknown-column error isn't one of the specially classified
                // SQLSTATEs - falls through to the generic execution-failure category.
                .isEqualTo(ErrorCategory.DB2_EXECUTION_FAILED);
    }

    // --- ADR 0010: traceability -----------------------------------------

    private ListAppender<ILoggingEvent> auditAppender;

    private ListAppender<ILoggingEvent> attachAuditListAppender() {
        ch.qos.logback.classic.Logger auditLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("AUDIT");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        auditLogger.addAppender(appender);
        this.auditAppender = appender;
        return appender;
    }

    @AfterEach
    void detachAuditListAppender() {
        if (auditAppender != null) {
            ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger("AUDIT")).detachAppender(auditAppender);
        }
    }

    @Test
    void executeQuery_writesRealAuditEventOnSuccess() {
        DataSource dataSource = ItsmH2Database.create("audit_success");
        Db2QueryAdapter adapter = newAdapter(dataSource);
        ListAppender<ILoggingEvent> appender = attachAuditListAppender();

        adapter.executeQuery("SELECT * FROM ITSM.TICKET");

        assertThat(appender.list).hasSize(1);
        String message = appender.list.get(0).getFormattedMessage();
        assertThat(message).contains("decision=ALLOWED");
        assertThat(message).contains("SELECT[ITSM.TICKET]");
        assertThat(message).contains("rowCount=2");
        assertThat(message).contains("truncated=false");
    }

    @Test
    void executeQuery_writesRealAuditEventOnSchemaRejection() {
        DataSource dataSource = ItsmH2Database.create("audit_rejection");
        Db2QueryAdapter adapter = newAdapter(dataSource);
        ListAppender<ILoggingEvent> appender = attachAuditListAppender();

        assertThatThrownBy(() -> adapter.executeQuery("SELECT * FROM OTHER_SCHEMA.SECRET"))
                .isInstanceOf(McpToolException.class);

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("decision=SCHEMA_NOT_ALLOWED");
    }

    @Test
    void executeQuery_writesRealAuditEventOnExecutionFailure() {
        DataSource dataSource = ItsmH2Database.create("audit_execution_failure");
        Db2QueryAdapter adapter = newAdapter(dataSource);
        ListAppender<ILoggingEvent> appender = attachAuditListAppender();

        assertThatThrownBy(() -> adapter.executeQuery("SELECT NONEXISTENT_COLUMN FROM ITSM.TICKET"))
                .isInstanceOf(McpToolException.class);

        assertThat(appender.list).hasSize(1);
        String message = appender.list.get(0).getFormattedMessage();
        assertThat(message).contains("decision=EXECUTION_FAILED");
        // The raw error/column name must not leak into the audit log either, same
        // requirement as the exception thrown back to the caller.
        assertThat(message).doesNotContain("NONEXISTENT_COLUMN");
    }

    @Test
    void executeQuery_correlationIdIsUniquePerCall() {
        DataSource dataSource = ItsmH2Database.create("audit_correlation");
        Db2QueryAdapter adapter = newAdapter(dataSource);
        ListAppender<ILoggingEvent> appender = attachAuditListAppender();

        QueryResult firstResult = adapter.executeQuery("SELECT * FROM ITSM.TICKET");
        QueryResult secondResult = adapter.executeQuery("SELECT * FROM ITSM.CUSTOMER");

        assertThat(appender.list).hasSize(2);
        String first = appender.list.get(0).getFormattedMessage();
        String second = appender.list.get(1).getFormattedMessage();
        String firstId = first.substring(first.indexOf('=') + 1, first.indexOf(' '));
        String secondId = second.substring(second.indexOf('=') + 1, second.indexOf(' '));
        assertThat(firstId).isNotEqualTo(secondId);
        // P1-3 (2026-09-01 master-brief gap review, #40, ADR 0041): a
        // successful call previously had no correlationId anywhere in its
        // own return value - only the audit log line and a thrown
        // exception carried one. Proven here by checking the two IDs
        // actually match, not just that QueryResult.correlationId() is
        // non-null.
        assertThat(firstResult.correlationId()).isEqualTo(firstId);
        assertThat(secondResult.correlationId()).isEqualTo(secondId);
    }

    // CR-012 (2026-08-14 code review, ADR 0025): a caller reporting a failed
    // tool call must have something to hand back for finding the matching
    // audit line - proven here by checking the two IDs actually match, not
    // just that both happen to exist.
    @Test
    void executeQuery_exceptionCorrelationIdMatchesTheAuditLogLine() {
        DataSource dataSource = ItsmH2Database.create("audit_correlation_matches_exception");
        Db2QueryAdapter adapter = newAdapter(dataSource);
        ListAppender<ILoggingEvent> appender = attachAuditListAppender();

        McpToolException thrown = catchThrowableOfType(
                () -> adapter.executeQuery("SELECT NONEXISTENT_COLUMN FROM ITSM.TICKET"), McpToolException.class);

        assertThat(thrown.correlationId()).isNotNull();
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("correlationId=" + thrown.correlationId());
        assertThat(thrown.getMessage()).contains("\"correlationId\":\"" + thrown.correlationId() + "\"");
    }

    // --- ADR 0011: observability metrics ---------------------------------

    @Test
    void executeQuery_recordsRealMetricsOnSuccess() {
        DataSource dataSource = ItsmH2Database.create("metrics_success");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Db2QueryAdapter adapter = newAdapter(dataSource, DEFAULT_LIMITS, registry);

        adapter.executeQuery("SELECT * FROM ITSM.TICKET");

        assertThat(registry.get("mcp.tool.calls").tag("tool", "executeQuery").tag("outcome", "allowed").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("mcp.tool.duration").tag("tool", "executeQuery").timer().count()).isEqualTo(1);
        assertThat(registry.get("mcp.result.rows").tag("tool", "executeQuery").summary().totalAmount()).isEqualTo(2.0);
        // P1-3 (2026-09-01 master-brief gap review, #40, ADR 0041): the
        // review found mcp.result.rows existed but no byte-size meter did.
        assertThat(registry.get("mcp.result.bytes").tag("tool", "executeQuery").summary().count()).isEqualTo(1);
        assertThat(registry.get("mcp.result.bytes").tag("tool", "executeQuery").summary().totalAmount()).isGreaterThan(0.0);
        assertThat(registry.get("db2.query.duration").tag("tool", "executeQuery").timer().count()).isEqualTo(1);
        assertThat(registry.get("security.policy.decisions")
                        .tag("profile", "SAFE_QUERY")
                        .tag("decision", "ALLOWED")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void executeQuery_recordsRealMetricsOnSchemaRejection() {
        DataSource dataSource = ItsmH2Database.create("metrics_rejection");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Db2QueryAdapter adapter = newAdapter(dataSource, DEFAULT_LIMITS, registry);

        assertThatThrownBy(() -> adapter.executeQuery("SELECT * FROM OTHER_SCHEMA.SECRET"))
                .isInstanceOf(McpToolException.class);

        assertThat(registry.get("mcp.tool.calls").tag("tool", "executeQuery").tag("outcome", "rejected").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("mcp.tool.rejected")
                        .tag("tool", "executeQuery")
                        .tag("reason", "SCHEMA_NOT_ALLOWED")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void executeQuery_recordsRealMetricsOnExecutionFailure() {
        DataSource dataSource = ItsmH2Database.create("metrics_failure");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Db2QueryAdapter adapter = newAdapter(dataSource, DEFAULT_LIMITS, registry);

        assertThatThrownBy(() -> adapter.executeQuery("SELECT NONEXISTENT_COLUMN FROM ITSM.TICKET"))
                .isInstanceOf(McpToolException.class);

        assertThat(registry.get("mcp.tool.calls").tag("tool", "executeQuery").tag("outcome", "failed").counter().count())
                .isEqualTo(1.0);
        // db2.errors is tagged by SQLSTATE, a bounded standard code space - not the
        // raw error message, satisfying brief 9.2's cardinality requirement.
        assertThat(registry.find("db2.errors").counter()).isNotNull();
        assertThat(registry.get("db2.query.duration").tag("tool", "executeQuery").timer().count()).isEqualTo(1);
    }

    // --- ADR 0012: OpenTelemetry spans -----------------------------------

    @Test
    void executeQuery_recordsRealSpanTreeOnSuccess() {
        DataSource dataSource = ItsmH2Database.create("spans_success");
        SimpleTracer tracer = new SimpleTracer();
        Db2QueryAdapter adapter = newAdapter(dataSource, DEFAULT_LIMITS, new SimpleMeterRegistry(), tracer);

        QueryResult result = adapter.executeQuery("SELECT * FROM ITSM.TICKET");

        List<String> spanNames = tracer.getSpans().stream().map(SimpleSpan::getName).toList();
        // SimpleTracer records spans in start order, not end order - verified here,
        // not assumed (an earlier version of this test expected the reverse, since
        // the root span's .end() call happens last in the finally block; that
        // assumption was wrong, not the instrumentation).
        assertThat(spanNames)
                .containsExactly(
                        "mcp.tool executeQuery",
                        "capability.authorize",
                        "input.validate",
                        "sql.validate",
                        "scope.authorize",
                        "sql.complexity",
                        "db2.execute");
        // P1-3 (2026-09-01 master-brief gap review, #40, ADR 0041): every
        // span.tag(...) call project-wide used to carry only "tool" -
        // never a correlationId, so a trace could never be tied back to a
        // specific audit log line or client-visible error. The root span
        // is first (SimpleTracer's start-order recording, confirmed above).
        assertThat(tracer.getSpans().getFirst().getTags()).containsEntry("correlationId", result.correlationId());
    }

    @Test
    void executeQuery_recordsPartialSpanTreeOnSchemaRejection() {
        DataSource dataSource = ItsmH2Database.create("spans_rejection");
        SimpleTracer tracer = new SimpleTracer();
        Db2QueryAdapter adapter = newAdapter(dataSource, DEFAULT_LIMITS, new SimpleMeterRegistry(), tracer);

        assertThatThrownBy(() -> adapter.executeQuery("SELECT * FROM OTHER_SCHEMA.SECRET"))
                .isInstanceOf(McpToolException.class);

        List<String> spanNames = tracer.getSpans().stream().map(SimpleSpan::getName).toList();
        // db2.execute never starts - the guard chain stopped it first, and the span
        // tree proves that, not just the thrown exception. Start order, per the
        // verified SimpleTracer behavior above (root first).
        assertThat(spanNames)
                .containsExactly(
                        "mcp.tool executeQuery", "capability.authorize", "input.validate", "sql.validate", "scope.authorize")
                .doesNotContain("db2.execute");
    }

    @Test
    void executeQuery_allSpansShareTheSameTraceId() {
        DataSource dataSource = ItsmH2Database.create("spans_trace_id");
        SimpleTracer tracer = new SimpleTracer();
        Db2QueryAdapter adapter = newAdapter(dataSource, DEFAULT_LIMITS, new SimpleMeterRegistry(), tracer);

        adapter.executeQuery("SELECT * FROM ITSM.TICKET");

        List<String> traceIds =
                tracer.getSpans().stream().map(s -> s.context().traceId()).distinct().toList();
        assertThat(traceIds).hasSize(1);
    }

    // --- CR-005 (2026-08-14 code review, ADR 0023): response payload/cell limits ---

    @Test
    void executeQuery_boundsAnOversizedClobCell() {
        DataSource dataSource = ItsmH2Database.create("clob_truncation");
        // Real java.sql.Clob (org.h2.jdbc.JdbcClob), verified against H2 before
        // writing Db2ValueNormalizer - not assumed from the JDBC spec alone.
        Db2QueryAdapter adapter = newAdapter(dataSource, new QueryLimitsProperties(1000, 30, 50, 1048576));

        QueryResult result = adapter.executeQuery("SELECT CAST(REPEAT('A', 500) AS CLOB) AS BIGCOL");

        String cell = (String) result.rows().get(0).get(0);
        assertThat(cell).contains("[cell truncated: exceeds 50-byte limit]");
        // P0-2 (2026-09-01 review): the suffix now counts against the same
        // 50-byte budget it announces, not appended on top of it - a true
        // hard cap, not "50 content bytes plus however long the marker is".
        assertThat(cell.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(50);
        assertThat(cell.length()).isLessThan(500);
    }

    @Test
    void executeQuery_boundsAnOversizedMultiByteClobCellByBytesNotCharacters() {
        DataSource dataSource = ItsmH2Database.create("clob_truncation_multibyte");
        // P0-2 (2026-09-01 review): the prior implementation budgeted CLOB
        // truncation by character count, not UTF-8 byte count - 100
        // 2-byte characters (200 bytes) fit inside a 100-*character* budget
        // and were returned whole, unbounded by the configured 100-*byte*
        // limit. This proves the fix reads real UTF-8 byte length instead.
        Db2QueryAdapter adapter = newAdapter(dataSource, new QueryLimitsProperties(1000, 30, 100, 1048576));

        QueryResult result = adapter.executeQuery("SELECT CAST(REPEAT('é', 100) AS CLOB) AS BIGCOL");

        String cell = (String) result.rows().get(0).get(0);
        assertThat(cell).contains("[cell truncated: exceeds 100-byte limit]");
        assertThat(cell.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(100);
    }

    @Test
    void executeQuery_omitsBlobContentEntirely() {
        DataSource dataSource = ItsmH2Database.create("blob_omission");
        Db2QueryAdapter adapter = newAdapter(dataSource);

        QueryResult result = adapter.executeQuery("SELECT CAST(REPEAT('B', 500) AS BLOB) AS BIGCOL");

        // No binary bytes at all in the result - explicit omission, not a
        // bounded/base64 representation (CR-005's other allowed option).
        assertThat(result.rows().get(0).get(0)).isEqualTo("<binary value omitted, 500 bytes>");
    }

    @Test
    void executeQuery_truncatesWhenPayloadBudgetExceededBeforeRowCap() {
        DataSource dataSource = ItsmH2Database.create("payload_budget");
        // maxRows stays generous (1000); only the payload budget is tight -
        // proves this is a genuinely independent limit, not a side effect of
        // the row cap.
        Db2QueryAdapter adapter = newAdapter(dataSource, new QueryLimitsProperties(1000, 30, 16384, 500));

        QueryResult result = adapter.executeQuery("SELECT N, REPEAT('X', 100) AS PADDING FROM ITSM.BULK_ROWS ORDER BY N");

        assertThat(result.truncated()).isTrue();
        assertThat(result.rowCount()).isLessThan(1000);
        assertThat(result.rowCount()).isGreaterThan(0);
    }

    @Test
    void executeQuery_refusesWhenEvenTheEmptyEnvelopeIsOverBudget() {
        DataSource dataSource = ItsmH2Database.create("payload_budget_single_row");
        // #62 (2026-09-09 re-review, RR-P0-1): a 10-byte budget cannot fit
        // even a zero-row response's own envelope - the column descriptors
        // and correlation ID alone are far larger. The previous behaviour
        // returned that envelope anyway, with zero rows and truncated=true,
        // which is a response *over* the configured cap dressed up as
        // enforcement of it. Refusing is the only answer that keeps the
        // bound hard.
        Db2QueryAdapter adapter = newAdapter(dataSource, new QueryLimitsProperties(1000, 30, 16384, 10));

        assertThatThrownBy(() ->
                        adapter.executeQuery("SELECT N, REPEAT('X', 100) AS PADDING FROM ITSM.BULK_ROWS ORDER BY N"))
                .isInstanceOf(McpToolException.class)
                .extracting(e -> ((McpToolException) e).category())
                .isEqualTo(ErrorCategory.RESULT_LIMIT_EXCEEDED);
    }

    @Test
    void executeQuery_serializedResponseNeverExceedsTheBudget() throws Exception {
        DataSource dataSource = ItsmH2Database.create("payload_budget_hard_bound");
        // The property the whole finding is about: not "rows were dropped",
        // but "what actually crosses the boundary fits". Measured on the
        // real serialized response, envelope included.
        int budget = 2000;
        Db2QueryAdapter adapter = newAdapter(dataSource, new QueryLimitsProperties(1000, 30, 16384, budget));

        QueryResult result =
                adapter.executeQuery("SELECT N, REPEAT('X', 100) AS PADDING FROM ITSM.BULK_ROWS ORDER BY N");

        assertThat(new ObjectMapper().writeValueAsBytes(result).length).isLessThanOrEqualTo(budget);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void executeQuery_serializedResponseFitsWithMultibyteAndEscapedContent() throws Exception {
        DataSource dataSource = ItsmH2Database.create("payload_budget_unicode");
        // Multibyte and JSON-escaped content are exactly where a
        // character-count or per-cell-content model understates the real
        // serialized size - "é" is two UTF-8 bytes, a quote or
        // backslash becomes two characters once escaped.
        int budget = 1500;
        Db2QueryAdapter adapter = newAdapter(dataSource, new QueryLimitsProperties(1000, 30, 16384, budget));

        QueryResult result = adapter.executeQuery(
                "SELECT N, REPEAT('é\"\\', 40) AS PADDING FROM ITSM.BULK_ROWS ORDER BY N");

        assertThat(new ObjectMapper().writeValueAsBytes(result).length).isLessThanOrEqualTo(budget);
    }

    @Test
    void executeQuery_zeroRowResultStillFitsAndIsNotMarkedTruncated() throws Exception {
        DataSource dataSource = ItsmH2Database.create("payload_budget_zero_rows");
        int budget = 2000;
        Db2QueryAdapter adapter = newAdapter(dataSource, new QueryLimitsProperties(1000, 30, 16384, budget));

        QueryResult result = adapter.executeQuery("SELECT N FROM ITSM.BULK_ROWS WHERE N < 0");

        assertThat(result.rowCount()).isZero();
        assertThat(result.truncated()).isFalse();
        assertThat(new ObjectMapper().writeValueAsBytes(result).length).isLessThanOrEqualTo(budget);
    }

    @Test
    void executeQuery_oversizedFirstRowIsDroppedRatherThanAdmitted() throws Exception {
        DataSource dataSource = ItsmH2Database.create("payload_budget_oversized_first");
        // A budget big enough for the envelope but not for the first row:
        // the row must be dropped, never admitted as a "we have to return
        // at least one" exemption, and the response must still fit.
        int budget = 300;
        Db2QueryAdapter adapter = newAdapter(dataSource, new QueryLimitsProperties(1000, 30, 16384, budget));

        QueryResult result =
                adapter.executeQuery("SELECT REPEAT('X', 400) AS PADDING FROM ITSM.BULK_ROWS ORDER BY 1");

        assertThat(result.rowCount()).isZero();
        assertThat(result.truncated()).isTrue();
        assertThat(new ObjectMapper().writeValueAsBytes(result).length).isLessThanOrEqualTo(budget);
    }

    @Test
    void executeQuery_unsupportedDriverTypeBecomesASafeMarker() {
        DataSource dataSource = ItsmH2Database.create("unsupported_type");
        Db2QueryAdapter adapter = newAdapter(dataSource);

        // ARRAY has no case in Db2ValueNormalizer (DB2 itself has no array
        // type in scope here) - proves the fallback path is real, not just
        // theoretical, and never calls the unknown type's raw toString().
        QueryResult result = adapter.executeQuery("SELECT ARRAY['A', 'B'] AS ARR");

        Object cell = result.rows().get(0).get(0);
        assertThat(cell).isInstanceOf(String.class);
        assertThat((String) cell).startsWith("<unsupported value type:");
    }
}
