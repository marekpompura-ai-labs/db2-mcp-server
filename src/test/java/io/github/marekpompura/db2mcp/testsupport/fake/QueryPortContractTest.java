package io.github.marekpompura.db2mcp.testsupport.fake;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.marekpompura.db2mcp.adapter.db2.Db2QueryAdapter;
import io.github.marekpompura.db2mcp.adapter.db2.Db2SqlStateClassifier;
import io.github.marekpompura.db2mcp.adapter.observability.AuditLogger;
import io.github.marekpompura.db2mcp.adapter.observability.MetricsRecorder;
import io.github.marekpompura.db2mcp.adapter.observability.SpanRecorder;
import io.github.marekpompura.db2mcp.application.port.out.QueryPort;
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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.test.simple.SimpleTracer;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A genuinely reusable adapter contract suite (database-portability
 * extension section 12.1, ADR 0029): the exact same structural assertions
 * run against a real, H2-backed {@code Db2QueryAdapter} and against {@link
 * FakeDatabaseAdapter}, proving both implementations of {@link QueryPort}
 * satisfy the same contract - not just that they both compile against the
 * same interface, but that a caller genuinely cannot tell them apart by
 * the shape of what comes back. {@code QueryPort} is the one outbound port
 * that is fully Tier 1 (ADR 0009) for the real Db2 adapter, so this is the
 * one port where a live dual-adapter run is honestly possible in this
 * environment - {@code MetadataPort}'s seven catalog-backed methods are
 * Tier 2 and cannot be compared this way (see {@code
 * MetadataPortContractTest}).
 */
class QueryPortContractTest {

    // Db2QueryAdapter validates SQL structurally (ADR 0007); the fake doesn't
    // parse its argument at all. A single real SELECT satisfies both.
    private static final String SQL = "SELECT 1 AS N";

    static Stream<Named<Supplier<QueryPort>>> queryPorts() {
        return Stream.of(
                Named.of("Db2QueryAdapter (H2-backed)", QueryPortContractTest::newDb2QueryAdapter),
                Named.of("FakeDatabaseAdapter", FakeDatabaseAdapter::new));
    }

    private static final AtomicInteger DB_COUNTER = new AtomicInteger();

    private static Db2QueryAdapter newDb2QueryAdapter() {
        // A fresh unique in-memory database per invocation - this factory runs
        // once per test method (each re-evaluates the @MethodSource stream), and
        // H2's DB_CLOSE_DELAY=-1 keeps a same-named database alive for the whole
        // JVM, so reusing one literal name here would make the second test see
        // the first test's already-created ITSM schema and fail on CREATE SCHEMA.
        DataSource dataSource = ItsmH2Database.create("query_port_contract_" + DB_COUNTER.incrementAndGet());
        return new Db2QueryAdapter(
                dataSource,
                new CapabilityProperties(CapabilityProfile.SAFE_QUERY),
                new SqlStructuralValidator(),
                new SchemaAllowlistValidator(new QueryPolicyProperties(List.of("ITSM"))),
                new SqlComplexityValidator(
                        new CapabilityProperties(CapabilityProfile.SAFE_QUERY),
                        new QueryComplexityLimitsProperties(1000, 1000),
                        new AdvancedQueryComplexityLimitsProperties(1000, 1000)),
                new QueryLimitsProperties(1000, 30, 16384, 1048576),
                new AdvancedQueryLimitsProperties(5000, 120, 65536, 4194304),
                new SqlFingerprint(),
                new AuditLogger(),
                new MetricsRecorder(new SimpleMeterRegistry()),
                new SpanRecorder(new SimpleTracer()),
                new Db2SqlStateClassifier(),
                new io.github.marekpompura.db2mcp.configuration.ActiveStatementRegistry());
    }

    @ParameterizedTest
    @MethodSource("queryPorts")
    void executeQuery_returnsANonNullResult(Supplier<QueryPort> portSupplier) {
        QueryPort port = portSupplier.get();

        QueryResult result = port.executeQuery(SQL);

        assertThat(result).isNotNull();
        assertThat(result.columns()).isNotNull();
        assertThat(result.rows()).isNotNull();
    }

    @ParameterizedTest
    @MethodSource("queryPorts")
    void executeQuery_rowCountMatchesTheActualRowListSize(Supplier<QueryPort> portSupplier) {
        QueryPort port = portSupplier.get();

        QueryResult result = port.executeQuery(SQL);

        assertThat(result.rowCount()).isEqualTo(result.rows().size());
    }

    @ParameterizedTest
    @MethodSource("queryPorts")
    void executeQuery_everyRowHasOneValuePerColumn(Supplier<QueryPort> portSupplier) {
        QueryPort port = portSupplier.get();

        QueryResult result = port.executeQuery(SQL);

        assertThat(result.rows()).allSatisfy(row -> assertThat(row).hasSize(result.columns().size()));
    }
}
