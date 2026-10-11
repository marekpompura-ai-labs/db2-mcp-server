package io.github.marekpompura.db2mcp.adapter.db2;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.marekpompura.db2mcp.adapter.observability.AuditLogger;
import io.github.marekpompura.db2mcp.adapter.observability.MetricsRecorder;
import io.github.marekpompura.db2mcp.adapter.observability.SpanRecorder;
import io.github.marekpompura.db2mcp.domain.model.CapabilityManifest;
import io.github.marekpompura.db2mcp.domain.model.DatabaseDescriptor;
import io.github.marekpompura.db2mcp.domain.model.DatabaseProduct;
import io.github.marekpompura.db2mcp.domain.model.ProcedureDetail;
import io.github.marekpompura.db2mcp.domain.model.ProcedureInfo;
import io.github.marekpompura.db2mcp.domain.model.ProcedureParameterInfo;
import io.github.marekpompura.db2mcp.domain.model.ProcedureSource;
import io.github.marekpompura.db2mcp.domain.model.TableDependencyInfo;
import io.github.marekpompura.db2mcp.policy.capability.CapabilityProfile;
import io.github.marekpompura.db2mcp.policy.capability.CapabilityProperties;
import io.github.marekpompura.db2mcp.policy.sql.AdvancedQueryComplexityLimitsProperties;
import io.github.marekpompura.db2mcp.policy.sql.AdvancedQueryLimitsProperties;
import io.github.marekpompura.db2mcp.policy.sql.QueryComplexityLimitsProperties;
import io.github.marekpompura.db2mcp.policy.sql.QueryLimitsProperties;
import io.github.marekpompura.db2mcp.policy.sql.QueryPolicyProperties;
import io.github.marekpompura.db2mcp.policy.sql.SchemaAllowlistValidator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.test.simple.SimpleTracer;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Automates what `docs/demo/guided-scenarios.md`'s "Live-DB2 verification
 * checklist" (CR-003, CR-004, `getCapabilities`) previously only recorded as
 * a one-time, by-hand run (issue #44, 2026-09-01 master-brief gap review,
 * P1-7). Same self-skip convention as {@link Db2LiveConnectivityIT}: enabled
 * only when {@code DB2_HOST} is set, true in this project's own development
 * environment and in ordinary CI, false only in a job deliberately pointed
 * at a real DB2 instance.
 *
 * <p>Deliberately targets the real {@code itsm-schema.sql}/
 * {@code itsm-test-data.sql} fixtures the guided-scenarios checklist itself
 * names - {@code docs/demo/live-db2-test-prerequisites.md}'s runbook for
 * provisioning them into a reachable DB2 instance is what makes these
 * assertions meaningful, not incidental.
 */
@EnabledIfEnvironmentVariable(named = "DB2_HOST", matches = ".+")
class Db2LiveCatalogIT {

    private static DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.ibm.db2.jcc.DB2Driver");
        dataSource.setUrl("jdbc:db2://%s:%s/%s"
                .formatted(System.getenv("DB2_HOST"), System.getenv("DB2_PORT"), System.getenv("DB2_DATABASE")));
        dataSource.setUsername(System.getenv("DB2_USER"));
        dataSource.setPassword(System.getenv("DB2_PASSWORD"));
        return dataSource;
    }

    private static Db2MetadataAdapter newMetadataAdapter() {
        return new Db2MetadataAdapter(
                dataSource(),
                new CapabilityProperties(CapabilityProfile.ADVANCED_QUERY),
                new SchemaAllowlistValidator(new QueryPolicyProperties(List.of("ITSM"))),
                new QueryLimitsProperties(1000, 30, 16384, 1048576),
                new AdvancedQueryLimitsProperties(5000, 120, 65536, 4194304),
                new AuditLogger(),
                new MetricsRecorder(new SimpleMeterRegistry()),
                new SpanRecorder(new SimpleTracer()),
                new Db2SqlStateClassifier());
    }

    private static Db2CapabilityAdapter newCapabilityAdapter() {
        return new Db2CapabilityAdapter(
                dataSource(),
                new CapabilityProperties(CapabilityProfile.ADVANCED_QUERY),
                new io.github.marekpompura.db2mcp.adapter.db2.DatabaseSelectionProperties(DatabaseProduct.DB2),
                new QueryLimitsProperties(1000, 30, 16384, 1048576),
                new AdvancedQueryLimitsProperties(5000, 120, 65536, 4194304),
                new QueryComplexityLimitsProperties(15, 6),
                new AdvancedQueryComplexityLimitsProperties(30, 10),
                new AuditLogger(),
                new MetricsRecorder(new SimpleMeterRegistry()),
                new SpanRecorder(new SimpleTracer()));
    }

    // CR-003 (ADR 0022): STP_FIND_TICKETS is deliberately defined twice in
    // itsm-schema.sql with different parameter lists - proves specificName
    // genuinely disambiguates overloads, not that both calls coincidentally
    // return the same thing.
    @Test
    void listProcedures_returnsBothOverloadsOfStpFindTickets() {
        List<ProcedureInfo> procedures = newMetadataAdapter().listProcedures("ITSM").items();

        List<ProcedureInfo> stpFindTickets =
                procedures.stream().filter(p -> p.procedureName().equals("STP_FIND_TICKETS")).toList();

        assertThat(stpFindTickets).hasSize(2);
        assertThat(stpFindTickets.stream().map(ProcedureInfo::specificName))
                .containsExactlyInAnyOrder("STP_FIND_TICKETS_BY_STATUS", "STP_FIND_TICKETS_BY_CUSTOMER");
    }

    @Test
    void describeProcedure_distinguishesOverloadsBySpecificName() {
        Db2MetadataAdapter adapter = newMetadataAdapter();

        ProcedureDetail byStatus = adapter.describeProcedure("ITSM", "STP_FIND_TICKETS_BY_STATUS");
        ProcedureDetail byCustomer = adapter.describeProcedure("ITSM", "STP_FIND_TICKETS_BY_CUSTOMER");

        assertThat(byStatus.parameters()).hasSize(2);
        assertThat(byStatus.parameters().stream().map(ProcedureParameterInfo::parameterName))
                .containsExactlyInAnyOrder("P_STATUS", "P_PRIORITY");

        assertThat(byCustomer.parameters()).hasSize(1);
        assertThat(byCustomer.parameters().get(0).parameterName()).isEqualTo("P_CUSTOMER_ID");
    }

    @Test
    void getProcedureSource_returnsOnlyItsOwnOverloadsBody() {
        Db2MetadataAdapter adapter = newMetadataAdapter();

        ProcedureSource byStatus = adapter.getProcedureSource("ITSM", "STP_FIND_TICKETS_BY_STATUS");
        ProcedureSource byCustomer = adapter.getProcedureSource("ITSM", "STP_FIND_TICKETS_BY_CUSTOMER");

        assertThat(byStatus.sourceText()).contains("P_STATUS").doesNotContain("P_CUSTOMER_ID");
        assertThat(byCustomer.sourceText()).contains("P_CUSTOMER_ID").doesNotContain("P_STATUS");
    }

    // CR-004 (ADR 0022): SERVICE_CATALOG_NOTE.SERVICE_NAME references
    // SERVICE_CATALOG's unique constraint on SERVICE_NAME, not the primary
    // key SERVICE_ID - the one case in this schema where the referenced key
    // genuinely isn't the parent's PK, exactly what REFKEYNAME-based
    // resolution (as opposed to assuming the referenced key is always the
    // PK) exists to get right.
    @Test
    void getTableDependencies_resolvesForeignKeyReferencingAUniqueConstraintNotThePrimaryKey() {
        List<TableDependencyInfo> dependencies = newMetadataAdapter().getTableDependencies("ITSM", "SERVICE_CATALOG").items();

        Optional<TableDependencyInfo> inbound = dependencies.stream()
                .filter(d -> "INBOUND".equals(d.direction()) && "SERVICE_CATALOG_NOTE".equals(d.fkTable()))
                .findFirst();

        assertThat(inbound).isPresent();
        assertThat(inbound.get().constraintName()).isEqualTo("FK_NOTE_SERVICE_NAME");
        assertThat(inbound.get().pkColumn()).isEqualTo("SERVICE_NAME");
    }

    // getCapabilities (ADR 0028): the first proof this server is actually
    // talking to DB2 and not silently degrading - productVersion is a real,
    // live driver-reported value, not the graceful-degradation fallback
    // Db2CapabilityAdapter falls back to when the live query fails.
    @Test
    void capabilities_reportsARealLiveDatabaseProductVersion_notTheDegradationFallback() {
        CapabilityManifest manifest = newCapabilityAdapter().capabilities();

        DatabaseDescriptor database = manifest.database();
        assertThat(database.product()).isEqualTo(DatabaseProduct.DB2);
        assertThat(database.productVersion()).isNotEqualTo("(unavailable - could not connect)");
        // IBM's JDBC driver reports its own internal SQLvvrrmm-style string
        // (e.g. "SQL120150"), not the CLP connect-banner format - documented
        // directly in guided-scenarios.md after the first live run corrected
        // an earlier, incorrect assumption about the format. Asserting the
        // SQL-prefixed shape, not an exact version string, so this test
        // doesn't need updating every time the DB2 instance is patched.
        assertThat(database.productVersion()).startsWith("SQL");
    }
}
