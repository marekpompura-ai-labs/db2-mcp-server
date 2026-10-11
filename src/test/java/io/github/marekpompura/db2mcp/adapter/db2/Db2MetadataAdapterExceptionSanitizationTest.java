package io.github.marekpompura.db2mcp.adapter.db2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.marekpompura.db2mcp.adapter.observability.AuditLogger;
import io.github.marekpompura.db2mcp.adapter.observability.MetricsRecorder;
import io.github.marekpompura.db2mcp.adapter.observability.SpanRecorder;
import io.github.marekpompura.db2mcp.domain.error.ErrorCategory;
import io.github.marekpompura.db2mcp.domain.error.McpToolException;
import io.github.marekpompura.db2mcp.policy.capability.CapabilityProfile;
import io.github.marekpompura.db2mcp.policy.capability.CapabilityProperties;
import io.github.marekpompura.db2mcp.policy.sql.AdvancedQueryLimitsProperties;
import io.github.marekpompura.db2mcp.policy.sql.QueryLimitsProperties;
import io.github.marekpompura.db2mcp.policy.sql.QueryPolicyProperties;
import io.github.marekpompura.db2mcp.policy.sql.SchemaAllowlistValidator;
import io.github.marekpompura.db2mcp.testsupport.ItsmH2Database;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.test.simple.SimpleTracer;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

/**
 * CR-002 acceptance evidence (2026-08-14 code review): a genuine driver
 * exception from a metadata query must not reach the caller raw. The ITSM
 * H2 fixture (ADR 0009) has no {@code SYSCAT.SCHEMATA} view - exactly the
 * kind of real {@link DataAccessException} the review found propagating
 * unsanitized from every one of {@code Db2MetadataAdapter}'s seven
 * catalog-backed methods.
 *
 * <p>Also CR-009/CR-012 (ADR 0025): before that fix, metadata-tool failures
 * left no audit trace at all - {@code sanitizeErrors} only sanitized, never
 * recorded. Now every metadata tool audits like {@code executeQuery}
 * already did, and the thrown exception's {@code correlationId} matches
 * the audit line's, proven directly rather than assumed from the code
 * shape.
 */
class Db2MetadataAdapterExceptionSanitizationTest {

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

    @Test
    void listSchemas_sanitizesRealDataAccessException() {
        DataSource dataSource = ItsmH2Database.create("sanitization_list_schemas");
        Db2MetadataAdapter adapter = newAdapter(dataSource);

        assertThatThrownBy(adapter::listSchemas)
                .isInstanceOf(McpToolException.class)
                .isNotInstanceOf(DataAccessException.class)
                .extracting(t -> ((McpToolException) t).category())
                .isEqualTo(ErrorCategory.DB2_EXECUTION_FAILED);
    }

    @Test
    void listSchemas_messageContainsNoDriverOrCatalogDetail() {
        DataSource dataSource = ItsmH2Database.create("sanitization_list_schemas_message");
        Db2MetadataAdapter adapter = newAdapter(dataSource);

        assertThatThrownBy(adapter::listSchemas)
                .satisfies(t -> {
                    String message = t.getMessage();
                    assertThat(message).doesNotContainIgnoringCase("SYSCAT.SCHEMATA");
                    assertThat(message).doesNotContainIgnoringCase("jdbc:h2");
                    assertThat(message).doesNotContain("org.h2.jdbc");
                });
    }

    // --- CR-009/CR-012 (ADR 0025): all-tool observability, correlation ID ---

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
    void listSchemas_writesAuditEventWithCorrelationIdMatchingTheThrownException() {
        DataSource dataSource = ItsmH2Database.create("sanitization_list_schemas_audit");
        Db2MetadataAdapter adapter = newAdapter(dataSource);
        ListAppender<ILoggingEvent> appender = attachAuditListAppender();

        McpToolException thrown = catchThrowableOfType(adapter::listSchemas, McpToolException.class);

        assertThat(thrown.correlationId()).isNotNull();
        assertThat(appender.list).hasSize(1);
        String auditMessage = appender.list.get(0).getFormattedMessage();
        assertThat(auditMessage).contains("correlationId=" + thrown.correlationId());
        assertThat(auditMessage).contains("tool=listSchemas");
        assertThat(auditMessage).contains("decision=EXECUTION_FAILED");
        // Before ADR 0025, no audit event existed for this path at all - this
        // assertion would have failed against an empty appender.list.
        assertThat(thrown.getMessage()).contains("\"correlationId\":\"" + thrown.correlationId() + "\"");
    }

    // listTables (like all seven pure metadata methods) queries SYSCAT.TABLES,
    // a real DB2 catalog view H2 has no equivalent for (ADR 0009) - there is
    // no way to exercise an ALLOWED outcome for these methods in this
    // environment, only EXECUTION_FAILED. Proves the instrumentation
    // generalizes to a second method, not only listSchemas.
    @Test
    void listTables_writesAuditEventOnFailure() {
        DataSource dataSource = ItsmH2Database.create("list_tables_audit_failure");
        Db2MetadataAdapter adapter = newAdapter(dataSource);
        ListAppender<ILoggingEvent> appender = attachAuditListAppender();

        assertThatThrownBy(() -> adapter.listTables("ITSM")).isInstanceOf(McpToolException.class);

        assertThat(appender.list).hasSize(1);
        String message = appender.list.get(0).getFormattedMessage();
        assertThat(message).contains("tool=listTables");
        assertThat(message).contains("decision=EXECUTION_FAILED");
        assertThat(message).contains("sql=TABLES[ITSM]");
    }
}
