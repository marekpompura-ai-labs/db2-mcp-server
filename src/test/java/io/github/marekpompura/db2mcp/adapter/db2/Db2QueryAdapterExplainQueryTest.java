package io.github.marekpompura.db2mcp.adapter.db2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.github.marekpompura.db2mcp.adapter.observability.AuditLogger;
import io.github.marekpompura.db2mcp.adapter.observability.MetricsRecorder;
import io.github.marekpompura.db2mcp.adapter.observability.SpanRecorder;
import io.github.marekpompura.db2mcp.domain.error.ErrorCategory;
import io.github.marekpompura.db2mcp.domain.error.McpToolException;
import io.github.marekpompura.db2mcp.domain.model.ExplainOperator;
import io.github.marekpompura.db2mcp.domain.model.ExplainPlanResult;
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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.test.simple.SimpleTracer;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Coverage for {@code Db2QueryAdapter.explainQuery} (ADR 0033) that this
 * environment can honestly provide: the capability/structural/schema gates,
 * which reject before ever touching the {@code DataSource}, and the
 * truncation logic, which is this project's own code rather than DB2
 * behavior. Deliberately does NOT attempt to mock the {@code EXPLAIN PLAN
 * FOR} + EXPLAIN_OPERATOR read-back JDBC round trip itself - per ADR 0009,
 * EXPLAIN tables are DB2 catalog machinery with no H2 equivalent, and a
 * mocked-JDBC "happy path" test here would only prove self-consistency with
 * this code's own assumptions about DB2's behavior, not that those
 * assumptions are correct - exactly the false confidence ADR 0009 warns
 * against. That round trip is unverified until the live-DB2 checklist (ADR
 * 0033, docs/demo/guided-scenarios.md) is run.
 */
class Db2QueryAdapterExplainQueryTest {

    private static Db2QueryAdapter newAdapter(CapabilityProfile profile, DataSource dataSource) {
        return new Db2QueryAdapter(
                dataSource,
                new CapabilityProperties(profile),
                new SqlStructuralValidator(),
                new SchemaAllowlistValidator(new QueryPolicyProperties(List.of("ITSM"))),
                new SqlComplexityValidator(
                        new CapabilityProperties(profile),
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
    @CsvSource({"METADATA_ONLY", "SAFE_QUERY"})
    void explainQuery_rejectsEverythingButAdvancedQueryBeforeTouchingTheDataSource(CapabilityProfile profile) {
        DataSource dataSource = mock(DataSource.class);
        Db2QueryAdapter adapter = newAdapter(profile, dataSource);

        assertThatThrownBy(() -> adapter.explainQuery("SELECT * FROM ITSM.TICKET"))
                .isInstanceOf(McpToolException.class)
                .extracting(e -> ((McpToolException) e).category())
                .isEqualTo(ErrorCategory.CAPABILITY_DISABLED);
        verifyNoInteractions(dataSource);
    }

    @Test
    void explainQuery_rejectsNonSelectStatementsBeforeTouchingTheDataSource() {
        DataSource dataSource = mock(DataSource.class);
        Db2QueryAdapter adapter = newAdapter(CapabilityProfile.ADVANCED_QUERY, dataSource);

        assertThatThrownBy(() -> adapter.explainQuery("DELETE FROM ITSM.TICKET"))
                .isInstanceOf(McpToolException.class)
                .extracting(e -> ((McpToolException) e).category())
                .isEqualTo(ErrorCategory.VALIDATION_FAILED);
        verifyNoInteractions(dataSource);
    }

    @Test
    void explainQuery_rejectsSchemaOutsideAllowlistBeforeTouchingTheDataSource() {
        DataSource dataSource = mock(DataSource.class);
        Db2QueryAdapter adapter = newAdapter(CapabilityProfile.ADVANCED_QUERY, dataSource);

        assertThatThrownBy(() -> adapter.explainQuery("SELECT * FROM OTHER_SCHEMA.SECRET"))
                .isInstanceOf(McpToolException.class)
                .extracting(e -> ((McpToolException) e).category())
                .isEqualTo(ErrorCategory.SCOPE_DENIED);
        verifyNoInteractions(dataSource);
    }

    // --- truncateOperators: this project's own logic, not DB2 behavior ---

    @Test
    void truncateOperators_passesThroughWhenUnderTheCap() {
        List<ExplainOperator> operators = List.of(new ExplainOperator(1, "TBSCAN", 12.5));

        ExplainPlanResult result = Db2QueryAdapter.truncateOperators("SELECT 1", operators, 5, "test-correlation-id");

        assertThat(result.statementText()).isEqualTo("SELECT 1");
        assertThat(result.operators()).isEqualTo(operators);
        assertThat(result.truncated()).isFalse();
        // P1-3 (2026-09-01 master-brief gap review, #40, ADR 0041):
        // ExplainPlanResult previously had no correlationId field at all.
        assertThat(result.correlationId()).isEqualTo("test-correlation-id");
    }

    @Test
    void truncateOperators_truncatesAndFlagsWhenOverTheCap() {
        List<ExplainOperator> operators = List.of(
                new ExplainOperator(1, "TBSCAN", 12.5),
                new ExplainOperator(2, "SORT", 15.0),
                new ExplainOperator(3, "RETURN", 15.0));

        ExplainPlanResult result = Db2QueryAdapter.truncateOperators("SELECT 1", operators, 2, "test-correlation-id");

        assertThat(result.operators()).hasSize(2);
        assertThat(result.operators()).containsExactly(operators.get(0), operators.get(1));
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void truncateOperators_exactlyAtCapIsNotTruncated() {
        List<ExplainOperator> operators =
                List.of(new ExplainOperator(1, "TBSCAN", 12.5), new ExplainOperator(2, "RETURN", 12.5));

        ExplainPlanResult result = Db2QueryAdapter.truncateOperators("SELECT 1", operators, 2, "test-correlation-id");

        assertThat(result.truncated()).isFalse();
        assertThat(result.operators()).hasSize(2);
    }
}
