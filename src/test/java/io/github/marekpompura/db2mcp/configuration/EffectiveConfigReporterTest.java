package io.github.marekpompura.db2mcp.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.marekpompura.db2mcp.adapter.db2.DatabaseSelectionProperties;
import io.github.marekpompura.db2mcp.adapter.observability.AuditProperties;
import io.github.marekpompura.db2mcp.domain.model.DatabaseProduct;
import io.github.marekpompura.db2mcp.policy.capability.CapabilityProfile;
import io.github.marekpompura.db2mcp.policy.capability.CapabilityProperties;
import io.github.marekpompura.db2mcp.policy.sql.AdvancedQueryLimitsProperties;
import io.github.marekpompura.db2mcp.policy.sql.QueryLimitsProperties;
import io.github.marekpompura.db2mcp.policy.sql.QueryPolicyProperties;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.mock.env.MockEnvironment;

/**
 * Verifies the effective-config report (ADR 0015) both says what it should
 * and - just as important - never says what it shouldn't: no credential
 * value appears in the logged line under any circumstance tested here,
 * including a deliberately hostile datasource URL that embeds one.
 */
class EffectiveConfigReporterTest {

    private ListAppender<ILoggingEvent> appender;

    private ListAppender<ILoggingEvent> attachAppender() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(EffectiveConfigReporter.class);
        ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
        listAppender.start();
        logger.addAppender(listAppender);
        this.appender = listAppender;
        return listAppender;
    }

    @AfterEach
    void detachAppender() {
        if (appender != null) {
            ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(EffectiveConfigReporter.class))
                    .detachAppender(appender);
        }
    }

    @Test
    void logsSafeConfigurationValuesOnReady() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.datasource.url", "jdbc:db2://db2host:50000/ITSMDB");
        environment.setProperty("spring.datasource.hikari.maximum-pool-size", "5");
        environment.setProperty("spring.datasource.hikari.read-only", "true");

        EffectiveConfigReporter reporter = new EffectiveConfigReporter(
                new CapabilityProperties(CapabilityProfile.SAFE_QUERY),
                new QueryPolicyProperties(List.of("ITSM")),
                new QueryLimitsProperties(500, 15, 16384, 1048576),
                new AdvancedQueryLimitsProperties(5000, 120, 65536, 4194304),
                new AuditProperties("logs/custom-audit.log"),
                new DatabaseSelectionProperties(DatabaseProduct.DB2),
                environment);

        ListAppender<ILoggingEvent> events = attachAppender();
        reporter.logEffectiveConfiguration();

        assertThat(events.list).hasSize(1);
        String message = events.list.get(0).getFormattedMessage();
        assertThat(message)
                .contains("databaseProduct=DB2")
                .contains("SAFE_QUERY")
                .contains("allowedSchemaCount=1")
                .contains("500")
                .contains("15")
                .contains("auditFileConfigured=true")
                .contains("datasourceScheme=jdbc:db2")
                .contains("hikariMaximumPoolSize=5")
                .contains("hikariReadOnly=true")
                // P0-3 (2026-09-01 review): schema names, the audit file's
                // filesystem path, and the datasource host/port/database are
                // no longer logged - only safe categories/counts are.
                .doesNotContain("ITSM")
                .doesNotContain("logs/custom-audit.log")
                .doesNotContain("db2host")
                .doesNotContain("50000")
                .doesNotContain("ITSMDB");
    }

    @Test
    void logsAdvancedQueryLimitsNotBaseLimitsWhenAdvancedQueryProfileIsActive() {
        // ADR 0032: an operator checking this log under ADVANCED_QUERY must see
        // the limits actually enforced, not always the SAFE_QUERY defaults -
        // deliberately gives base and advanced very different numbers so a
        // regression back to always-logging-base would fail this assertion.
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.datasource.url", "jdbc:db2://db2host:50000/ITSMDB");

        EffectiveConfigReporter reporter = new EffectiveConfigReporter(
                new CapabilityProperties(CapabilityProfile.ADVANCED_QUERY),
                new QueryPolicyProperties(List.of("ITSM")),
                new QueryLimitsProperties(1000, 30, 16384, 1048576),
                new AdvancedQueryLimitsProperties(9999, 111, 22222, 3333333),
                new AuditProperties("logs/audit.log"),
                new DatabaseSelectionProperties(DatabaseProduct.DB2),
                environment);

        ListAppender<ILoggingEvent> events = attachAppender();
        reporter.logEffectiveConfiguration();

        String message = events.list.get(0).getFormattedMessage();
        assertThat(message)
                .contains("ADVANCED_QUERY")
                .contains("maxRows=9999")
                .contains("timeoutSeconds=111")
                .contains("maxCellBytes=22222")
                .contains("maxPayloadBytes=3333333")
                .doesNotContain("maxRows=1000");
    }

    @Test
    void neverLogsAnyCredentialValueEvenWhenUrlEmbedsOne() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.datasource.url", "jdbc:db2://scott:tiger@db2host:50000/ITSMDB");

        EffectiveConfigReporter reporter = new EffectiveConfigReporter(
                new CapabilityProperties(CapabilityProfile.METADATA_ONLY),
                new QueryPolicyProperties(List.of()),
                new QueryLimitsProperties(1000, 30, 16384, 1048576),
                new AdvancedQueryLimitsProperties(5000, 120, 65536, 4194304),
                new AuditProperties("logs/audit.log"),
                new DatabaseSelectionProperties(DatabaseProduct.DB2),
                environment);

        ListAppender<ILoggingEvent> events = attachAppender();
        reporter.logEffectiveConfiguration();

        String message = events.list.get(0).getFormattedMessage();
        assertThat(message).doesNotContain("scott").doesNotContain("tiger");
    }

    @Test
    void neverThrowsWhenDatasourceUrlPlaceholdersAreUnresolved() {
        // Real bug found while smoke-testing this class: DB2_HOST/DB2_PORT/DB2_DATABASE
        // being unset is an expected state before secrets are wired up, and the
        // application itself starts fine in that state - a diagnostic reporter must
        // not be the thing that crashes startup where the app otherwise wouldn't.
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.datasource.url", "jdbc:db2://${DB2_HOST}:${DB2_PORT}/${DB2_DATABASE}");

        EffectiveConfigReporter reporter = new EffectiveConfigReporter(
                new CapabilityProperties(CapabilityProfile.METADATA_ONLY),
                new QueryPolicyProperties(List.of()),
                new QueryLimitsProperties(1000, 30, 16384, 1048576),
                new AdvancedQueryLimitsProperties(5000, 120, 65536, 4194304),
                new AuditProperties("logs/audit.log"),
                new DatabaseSelectionProperties(DatabaseProduct.DB2),
                environment);

        ListAppender<ILoggingEvent> events = attachAppender();

        org.assertj.core.api.Assertions.assertThatCode(reporter::logEffectiveConfiguration)
                .doesNotThrowAnyException();
        assertThat(events.list.get(0).getFormattedMessage()).contains("unresolvable");
    }

    @Test
    void isRegisteredForApplicationReadyEvent() throws NoSuchMethodException {
        var method = EffectiveConfigReporter.class.getMethod("logEffectiveConfiguration");
        var listener = method.getAnnotation(org.springframework.context.event.EventListener.class);

        assertThat(listener).isNotNull();
        assertThat(listener.value()).containsExactly(ApplicationReadyEvent.class);
    }
}
