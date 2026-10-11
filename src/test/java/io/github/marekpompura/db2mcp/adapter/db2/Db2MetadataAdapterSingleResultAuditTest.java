package io.github.marekpompura.db2mcp.adapter.db2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.marekpompura.db2mcp.adapter.observability.AuditLogger;
import io.github.marekpompura.db2mcp.adapter.observability.MetricsRecorder;
import io.github.marekpompura.db2mcp.adapter.observability.SpanRecorder;
import io.github.marekpompura.db2mcp.domain.model.ProcedureDetail;
import io.github.marekpompura.db2mcp.domain.model.ProcedureSource;
import io.github.marekpompura.db2mcp.policy.capability.CapabilityProfile;
import io.github.marekpompura.db2mcp.policy.capability.CapabilityProperties;
import io.github.marekpompura.db2mcp.policy.sql.AdvancedQueryLimitsProperties;
import io.github.marekpompura.db2mcp.policy.sql.QueryLimitsProperties;
import io.github.marekpompura.db2mcp.policy.sql.QueryPolicyProperties;
import io.github.marekpompura.db2mcp.policy.sql.SchemaAllowlistValidator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.test.simple.SimpleTracer;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Regression coverage for a real bug found while building {@code
 * getCapabilities} (ADR 0028): {@code describeProcedure}/{@code
 * getProcedureSource}'s successful (ALLOWED) outcome has no {@code List}
 * result to size, so {@code Db2MetadataAdapter.instrumented} passes a
 * {@code null} row count into the audit event - which a prior,
 * primitive-typed {@link io.github.marekpompura.db2mcp.domain.audit.AuditEvent#allowed}
 * unboxed and threw a {@code NullPointerException} on. No test exercised
 * this path before now: both methods are Tier 2 (ADR 0009, no {@code
 * SYSCAT.PROCEDURES} equivalent in H2), so the only way to reach the
 * ALLOWED branch at all is a hand-built synthetic catalog table, deliberately
 * <em>not</em> presented as evidence of real DB2 catalog behavior - only of
 * this class's own instrumentation wiring not crashing on a single-object
 * result.
 */
class Db2MetadataAdapterSingleResultAuditTest {

    private ListAppender<ILoggingEvent> auditAppender;

    private static DataSource syntheticProcedureCatalog(String uniqueName) throws SQLException {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setUrl("jdbc:h2:mem:" + uniqueName + ";MODE=DB2;DB_CLOSE_DELAY=-1");
        try (Connection connection = dataSource.getConnection()) {
            connection.createStatement().execute("CREATE SCHEMA SYSCAT");
            connection
                    .createStatement()
                    .execute(
                            "CREATE TABLE SYSCAT.PROCEDURES (PROCSCHEMA VARCHAR(128), PROCNAME VARCHAR(128), "
                                    + "SPECIFICNAME VARCHAR(128), LANGUAGE VARCHAR(20), PARM_COUNT INT, TEXT VARCHAR(4000))");
            connection
                    .createStatement()
                    .execute(
                            "CREATE TABLE SYSCAT.PROCPARMS (PROCSCHEMA VARCHAR(128), SPECIFICNAME VARCHAR(128), "
                                    + "PARMNAME VARCHAR(128), TYPENAME VARCHAR(128), PARM_MODE VARCHAR(6), ORDINAL INT)");
            connection
                    .createStatement()
                    .execute(
                            "INSERT INTO SYSCAT.PROCEDURES VALUES "
                                    + "('ITSM', 'STP_FIND_TICKETS', 'SQL240101120000000', 'SQL', 1, 'CREATE PROCEDURE ...')");
            connection
                    .createStatement()
                    .execute(
                            "INSERT INTO SYSCAT.PROCPARMS VALUES "
                                    + "('ITSM', 'SQL240101120000000', 'P_STATUS', 'CHARACTER', 'IN', 1)");
        }
        return dataSource;
    }

    private static Db2MetadataAdapter newAdapter(DataSource dataSource) {
        return new Db2MetadataAdapter(
                dataSource,
                new CapabilityProperties(CapabilityProfile.SAFE_QUERY),
                new SchemaAllowlistValidator(new QueryPolicyProperties(List.of("ITSM"))),
                new QueryLimitsProperties(1000, 30, 16384, 1048576),
                new AdvancedQueryLimitsProperties(5000, 120, 65536, 4194304),
                new AuditLogger(),
                new MetricsRecorder(new SimpleMeterRegistry()),
                new SpanRecorder(new SimpleTracer()),
                new Db2SqlStateClassifier());
    }

    private ListAppender<ILoggingEvent> attachAuditAppender() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("AUDIT");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        this.auditAppender = appender;
        return appender;
    }

    @AfterEach
    void detachAuditAppender() {
        if (auditAppender != null) {
            ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger("AUDIT")).detachAppender(auditAppender);
        }
    }

    @Test
    void describeProcedure_succeedsAndAuditsWithoutThrowing() throws SQLException {
        DataSource dataSource = syntheticProcedureCatalog("single_result_describe");
        Db2MetadataAdapter adapter = newAdapter(dataSource);
        ListAppender<ILoggingEvent> events = attachAuditAppender();

        ProcedureDetail detail = adapter.describeProcedure("ITSM", "SQL240101120000000");

        assertThat(detail.procedure().specificName()).isEqualTo("SQL240101120000000");
        assertThat(detail.parameters()).hasSize(1);
        assertThat(events.list).hasSize(1);
        assertThat(events.list.get(0).getFormattedMessage()).contains("decision=ALLOWED");
    }

    @Test
    void getProcedureSource_succeedsAndAuditsWithoutThrowing() throws SQLException {
        DataSource dataSource = syntheticProcedureCatalog("single_result_source");
        Db2MetadataAdapter adapter = newAdapter(dataSource);
        attachAuditAppender();

        assertThatCode(() -> {
                    ProcedureSource source = adapter.getProcedureSource("ITSM", "SQL240101120000000");
                    assertThat(source.sourceText()).isEqualTo("CREATE PROCEDURE ...");
                })
                .doesNotThrowAnyException();
    }
}
