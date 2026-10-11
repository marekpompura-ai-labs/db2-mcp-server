package io.github.marekpompura.db2mcp.testsupport.fake;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.marekpompura.db2mcp.adapter.db2.Db2MetadataAdapter;
import io.github.marekpompura.db2mcp.adapter.db2.Db2SqlStateClassifier;
import io.github.marekpompura.db2mcp.adapter.observability.AuditLogger;
import io.github.marekpompura.db2mcp.adapter.observability.MetricsRecorder;
import io.github.marekpompura.db2mcp.adapter.observability.SpanRecorder;
import io.github.marekpompura.db2mcp.application.port.out.MetadataPort;
import io.github.marekpompura.db2mcp.domain.model.ReferenceDataEntry;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code MetadataPort}'s dual-adapter contract evidence - deliberately
 * scoped to {@code lookupRefData} only, not all eight methods (ADR 0029).
 *
 * <p>The other seven methods ({@code listSchemas}, {@code listTables},
 * {@code describeTable}, {@code getTableDependencies}, {@code
 * listProcedures}, {@code describeProcedure}, {@code getProcedureSource})
 * are Tier 2 for the real Db2 adapter (ADR 0009 - no {@code
 * SYSCAT.PROCEDURES}/{@code SYSCAT.TABLES} equivalent in H2), so a
 * same-assertions-against-both-adapters run isn't honestly possible for
 * them in this environment - it would either fail on the Db2 side every
 * time (not a contract comparison, just Tier 2's existing limitation
 * again) or require a hand-built synthetic catalog table that proves
 * nothing about real DB2 behavior (the same caveat {@code
 * Db2MetadataAdapterSingleResultAuditTest} is explicit about). Those seven
 * methods' fake-side behavior is already exercised, through the real
 * {@code mcp.tool} classes, by {@link FakeAdapterMcpToolsTest} - this
 * class doesn't duplicate that coverage.
 *
 * <p>{@code lookupRefData} is the one {@code MetadataPort} method that is
 * fully Tier 1 for the real adapter (it queries a caller-named table
 * directly, not a Db2 system catalog view), so it's the one honest
 * dual-adapter comparison available for this port - assertions are
 * structural (non-null, non-empty, every entry has both fields populated),
 * not value-equality, since the real ITSM fixture and the fake's synthetic
 * data are intentionally different datasets.
 */
class MetadataPortContractTest {

    private static final AtomicInteger DB_COUNTER = new AtomicInteger();

    record LookupRefDataScenario(MetadataPort port, String schema, String table, String codeColumn, String descriptionColumn) {
    }

    static Stream<Named<LookupRefDataScenario>> lookupRefDataScenarios() {
        return Stream.of(
                Named.of(
                        "Db2MetadataAdapter (H2-backed)",
                        new LookupRefDataScenario(newDb2MetadataAdapter(), "ITSM", "REF_DATA", "CODE", "DESCRIPTION")),
                Named.of(
                        "FakeDatabaseAdapter",
                        new LookupRefDataScenario(
                                new FakeDatabaseAdapter(),
                                FakeDatabaseAdapter.SCHEMA,
                                FakeDatabaseAdapter.REF_TABLE,
                                "CODE",
                                "DESCRIPTION")));
    }

    private static Db2MetadataAdapter newDb2MetadataAdapter() {
        DataSource dataSource = ItsmH2Database.create("metadata_port_contract_" + DB_COUNTER.incrementAndGet());
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

    @ParameterizedTest
    @MethodSource("lookupRefDataScenarios")
    void lookupRefData_returnsNonEmptyCodeDescriptionPairs(LookupRefDataScenario scenario) {
        List<ReferenceDataEntry> entries = scenario.port()
                .lookupRefData(scenario.schema(), scenario.table(), scenario.codeColumn(), scenario.descriptionColumn(), null, null).items();

        assertThat(entries).isNotEmpty();
        assertThat(entries)
                .allSatisfy(entry -> assertThat(entry.code()).isNotBlank())
                .allSatisfy(entry -> assertThat(entry.description()).isNotBlank());
    }
}
