package io.github.marekpompura.db2mcp.adapter.db2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.marekpompura.db2mcp.adapter.observability.AuditLogger;
import io.github.marekpompura.db2mcp.adapter.observability.MetricsRecorder;
import io.github.marekpompura.db2mcp.adapter.observability.SpanRecorder;
import io.github.marekpompura.db2mcp.domain.error.ErrorCategory;
import io.github.marekpompura.db2mcp.domain.error.McpToolException;
import io.github.marekpompura.db2mcp.domain.model.QueryResult;
import io.github.marekpompura.db2mcp.domain.model.ReferenceDataEntry;
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
import io.github.marekpompura.db2mcp.testsupport.ItsmDemoH2Database;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.test.simple.SimpleTracer;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * Proves the Tier 1 claims in docs/demo/guided-scenarios.md (scenarios 4-6,
 * master brief section 17.1) against the real ITSM demo dataset, not just
 * against the general-purpose test fixture other integration tests use
 * (ADR 0009). Each test method is referenced by name from that document, so
 * the two stay honest about each other.
 */
class Db2DemoScenarioIntegrationTest {

    private static Db2QueryAdapter newQueryAdapter(DataSource dataSource) {
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

    private static Db2MetadataAdapter newMetadataAdapter(DataSource dataSource) {
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
    void lookupRefData_resolvesPriorityCodes() {
        DataSource dataSource = ItsmDemoH2Database.create("demo_scenario4_priority");
        Db2MetadataAdapter adapter = newMetadataAdapter(dataSource);

        var entries = adapter.lookupRefData("ITSM", "REF_DATA", "CODE", "DESCRIPTION", "CATEGORY", "PRIORITY").items();

        assertThat(entries)
                .containsExactly(
                        new ReferenceDataEntry("H", "High"),
                        new ReferenceDataEntry("L", "Low"),
                        new ReferenceDataEntry("N", "Normal"));
    }

    @Test
    void lookupRefData_resolvesStatusCodes() {
        DataSource dataSource = ItsmDemoH2Database.create("demo_scenario4_status");
        Db2MetadataAdapter adapter = newMetadataAdapter(dataSource);

        var entries = adapter.lookupRefData("ITSM", "REF_DATA", "CODE", "DESCRIPTION", "CATEGORY", "STATUS").items();

        assertThat(entries)
                .containsExactly(
                        new ReferenceDataEntry("CL", "Closed"),
                        new ReferenceDataEntry("IP", "In Progress"),
                        new ReferenceDataEntry("OP", "Open"));
    }

    @Test
    void executeQuery_returnsOpenHighPriorityTickets() {
        DataSource dataSource = ItsmDemoH2Database.create("demo_scenario5");
        Db2QueryAdapter adapter = newQueryAdapter(dataSource);

        QueryResult result = adapter.executeQuery(
                "SELECT TICKET_ID, TITLE, STATUS FROM ITSM.TICKET WHERE PRIORITY = 'H' AND STATUS = 'OP'");

        assertThat(result.rowCount()).isEqualTo(1);
        assertThat(result.truncated()).isFalse();
        assertThat(result.rows().get(0))
                .containsExactly(101, "Cannot connect to VPN from home network", "OP");
    }

    @Test
    void executeQuery_rejectsSchemaOutsideAllowlist() {
        DataSource dataSource = ItsmDemoH2Database.create("demo_scenario6_allowlist");
        Db2QueryAdapter adapter = newQueryAdapter(dataSource);

        assertThatThrownBy(() -> adapter.executeQuery("SELECT * FROM SYSCAT.TABLES"))
                .isInstanceOf(McpToolException.class)
                .extracting(t -> ((McpToolException) t).category())
                .isEqualTo(ErrorCategory.SCOPE_DENIED);
    }

    @Test
    void executeQuery_rejectsMultiStatementSql() {
        DataSource dataSource = ItsmDemoH2Database.create("demo_scenario6_multistatement");
        Db2QueryAdapter adapter = newQueryAdapter(dataSource);

        assertThatThrownBy(() -> adapter.executeQuery("SELECT 1; DROP TABLE ITSM.TICKET"))
                .isInstanceOf(McpToolException.class)
                .extracting(t -> ((McpToolException) t).category())
                .isEqualTo(ErrorCategory.VALIDATION_FAILED);

        // The DROP must never have run - TICKET is still there and still queryable.
        QueryResult afterRejection = adapter.executeQuery("SELECT TICKET_ID FROM ITSM.TICKET");
        assertThat(afterRejection.rowCount()).isEqualTo(4);
    }
}
