package io.github.marekpompura.db2mcp.adapter.db2;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.marekpompura.db2mcp.adapter.observability.AuditLogger;
import io.github.marekpompura.db2mcp.adapter.observability.MetricsRecorder;
import io.github.marekpompura.db2mcp.adapter.observability.SpanRecorder;
import io.github.marekpompura.db2mcp.domain.model.ProcedureInfo;
import io.github.marekpompura.db2mcp.policy.capability.CapabilityProfile;
import io.github.marekpompura.db2mcp.policy.capability.CapabilityProperties;
import io.github.marekpompura.db2mcp.policy.sql.AdvancedQueryLimitsProperties;
import io.github.marekpompura.db2mcp.policy.sql.QueryLimitsProperties;
import io.github.marekpompura.db2mcp.policy.sql.QueryPolicyProperties;
import io.github.marekpompura.db2mcp.policy.sql.SchemaAllowlistValidator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.test.simple.SimpleTracer;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

/**
 * P0-2 acceptance evidence (2026-09-01 master-brief gap review): before this
 * fix, the six pure metadata methods that list/describe more than one object
 * (all but {@code describeProcedure}/{@code getProcedureSource}, which
 * return a single object) had neither a row cap nor a query timeout at all -
 * only {@code lookupRefData} did. An unbounded catalog enumeration could
 * return arbitrarily many rows. {@code listProcedures} is exercised here
 * (the same synthetic-catalog technique {@code
 * Db2MetadataAdapterSingleResultAuditTest} uses, since {@code
 * SYSCAT.PROCEDURES} has no H2 equivalent - Tier 2, ADR 0009); the row cap
 * is wired identically for all six methods via the shared {@code
 * jdbcTemplate} field, not per-method.
 */
class Db2MetadataAdapterRowLimitTest {

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
                            "INSERT INTO SYSCAT.PROCEDURES VALUES "
                                    + "('ITSM', 'STP_ONE', 'SPECIFIC_1', 'SQL', 1, 'x'), "
                                    + "('ITSM', 'STP_TWO', 'SPECIFIC_2', 'SQL', 1, 'x'), "
                                    + "('ITSM', 'STP_THREE', 'SPECIFIC_3', 'SQL', 1, 'x')");
        }
        return dataSource;
    }

    private static Db2MetadataAdapter newAdapter(DataSource dataSource, int maxRows) {
        return new Db2MetadataAdapter(
                dataSource,
                new CapabilityProperties(CapabilityProfile.SAFE_QUERY),
                new SchemaAllowlistValidator(new QueryPolicyProperties(List.of("ITSM"))),
                new QueryLimitsProperties(maxRows, 30, 16384, 1048576),
                new AdvancedQueryLimitsProperties(5000, 120, 65536, 4194304),
                new AuditLogger(),
                new MetricsRecorder(new SimpleMeterRegistry()),
                new SpanRecorder(new SimpleTracer()),
                new Db2SqlStateClassifier());
    }

    private static Db2MetadataAdapter newAdapter(DataSource dataSource, int maxRows, MeterRegistry registry, Tracer tracer) {
        return new Db2MetadataAdapter(
                dataSource,
                new CapabilityProperties(CapabilityProfile.SAFE_QUERY),
                new SchemaAllowlistValidator(new QueryPolicyProperties(List.of("ITSM"))),
                new QueryLimitsProperties(maxRows, 30, 16384, 1048576),
                new AdvancedQueryLimitsProperties(5000, 120, 65536, 4194304),
                new AuditLogger(),
                new MetricsRecorder(registry),
                new SpanRecorder(tracer),
                new Db2SqlStateClassifier());
    }

    @Test
    void listProcedures_isBoundedByMaxRowsEvenThoughItHadNoLimitBefore() throws SQLException {
        DataSource dataSource = syntheticProcedureCatalog("row_limit_list_procedures");
        Db2MetadataAdapter adapter = newAdapter(dataSource, 1);

        List<ProcedureInfo> procedures = adapter.listProcedures("ITSM").items();

        assertThat(procedures).hasSize(1);
    }

    @Test
    void listProcedures_returnsEveryRowWhenUnderTheLimit() throws SQLException {
        DataSource dataSource = syntheticProcedureCatalog("row_limit_list_procedures_under");
        Db2MetadataAdapter adapter = newAdapter(dataSource, 1000);

        List<ProcedureInfo> procedures = adapter.listProcedures("ITSM").items();

        assertThat(procedures).hasSize(3);
    }

    // P1-3 (2026-09-01 master-brief gap review, #40, ADR 0041): the shared
    // `instrumented` helper backs six of the seven pure metadata methods -
    // proving the new result-bytes metric and correlationId span tag fire
    // here covers all six, not just listProcedures specifically.
    @Test
    void listProcedures_recordsResultBytesMetricAndTagsTheRootSpanWithCorrelationId() throws SQLException {
        DataSource dataSource = syntheticProcedureCatalog("row_limit_observability");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SimpleTracer tracer = new SimpleTracer();
        Db2MetadataAdapter adapter = newAdapter(dataSource, 1000, registry, tracer);

        adapter.listProcedures("ITSM");

        assertThat(registry.get("mcp.result.bytes").tag("tool", "listProcedures").summary().count()).isEqualTo(1);
        assertThat(registry.get("mcp.result.bytes").tag("tool", "listProcedures").summary().totalAmount())
                .isGreaterThan(0.0);
        assertThat(tracer.getSpans()).isNotEmpty();
        assertThat(tracer.getSpans().getFirst().getTags()).containsKey("correlationId");
    }
}
