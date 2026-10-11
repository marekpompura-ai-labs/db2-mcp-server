package io.github.marekpompura.db2mcp.adapter.db2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.marekpompura.db2mcp.adapter.observability.AuditLogger;
import io.github.marekpompura.db2mcp.adapter.observability.MetricsRecorder;
import io.github.marekpompura.db2mcp.adapter.observability.SpanRecorder;
import io.github.marekpompura.db2mcp.domain.model.CapabilityManifest;
import io.github.marekpompura.db2mcp.domain.model.DatabaseProduct;
import io.github.marekpompura.db2mcp.policy.capability.CapabilityProfile;
import io.github.marekpompura.db2mcp.policy.capability.CapabilityProperties;
import io.github.marekpompura.db2mcp.policy.sql.AdvancedQueryComplexityLimitsProperties;
import io.github.marekpompura.db2mcp.policy.sql.AdvancedQueryLimitsProperties;
import io.github.marekpompura.db2mcp.policy.sql.QueryComplexityLimitsProperties;
import io.github.marekpompura.db2mcp.policy.sql.QueryLimitsProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.test.simple.SimpleTracer;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * Pure logic coverage for {@code getCapabilities}' per-profile limits (ADR
 * 0032) - unrelated to {@code Db2CapabilityAdapterIntegrationTest}'s two
 * concerns (live productVersion query, graceful degradation on connection
 * failure), so it doesn't belong there. A mocked {@code DataSource} is
 * enough since nothing here depends on a real connection.
 */
class Db2CapabilityAdapterTest {

    private static final QueryLimitsProperties BASE_LIMITS = new QueryLimitsProperties(1000, 30, 16384, 1048576);
    private static final AdvancedQueryLimitsProperties ADVANCED_LIMITS =
            new AdvancedQueryLimitsProperties(5000, 120, 65536, 4194304);
    private static final QueryComplexityLimitsProperties BASE_COMPLEXITY_LIMITS =
            new QueryComplexityLimitsProperties(5, 3);
    private static final AdvancedQueryComplexityLimitsProperties ADVANCED_COMPLEXITY_LIMITS =
            new AdvancedQueryComplexityLimitsProperties(15, 6);

    private static Db2CapabilityAdapter newAdapter(CapabilityProfile profile) {
        return newAdapter(profile, new SimpleMeterRegistry(), new SimpleTracer());
    }

    private static Db2CapabilityAdapter newAdapter(
            CapabilityProfile profile, SimpleMeterRegistry registry, SimpleTracer tracer) {
        return new Db2CapabilityAdapter(
                mock(DataSource.class),
                new CapabilityProperties(profile),
                new DatabaseSelectionProperties(DatabaseProduct.DB2),
                BASE_LIMITS,
                ADVANCED_LIMITS,
                BASE_COMPLEXITY_LIMITS,
                ADVANCED_COMPLEXITY_LIMITS,
                new AuditLogger(),
                new MetricsRecorder(registry),
                new SpanRecorder(tracer));
    }

    @Test
    void capabilities_reportsBaseLimitsUnderSafeQuery() {
        CapabilityManifest manifest = newAdapter(CapabilityProfile.SAFE_QUERY).capabilities();

        assertThat(manifest.limits().maxRows()).isEqualTo(BASE_LIMITS.maxRows());
        assertThat(manifest.limits().maxCellBytes()).isEqualTo(BASE_LIMITS.maxCellBytes());
        assertThat(manifest.limits().maxPayloadBytes()).isEqualTo(BASE_LIMITS.maxPayloadBytes());
        assertThat(manifest.limits().maxJoins()).isEqualTo(BASE_COMPLEXITY_LIMITS.maxJoins());
        assertThat(manifest.limits().maxNestingDepth()).isEqualTo(BASE_COMPLEXITY_LIMITS.maxNestingDepth());
    }

    @Test
    void capabilities_reportsBaseLimitsUnderMetadataOnly() {
        CapabilityManifest manifest = newAdapter(CapabilityProfile.METADATA_ONLY).capabilities();

        assertThat(manifest.limits().maxRows()).isEqualTo(BASE_LIMITS.maxRows());
    }

    @Test
    void capabilities_reportsAdvancedLimitsUnderAdvancedQuery() {
        CapabilityManifest manifest = newAdapter(CapabilityProfile.ADVANCED_QUERY).capabilities();

        assertThat(manifest.limits().maxRows()).isEqualTo(ADVANCED_LIMITS.maxRows());
        assertThat(manifest.limits().maxCellBytes()).isEqualTo(ADVANCED_LIMITS.maxCellBytes());
        assertThat(manifest.limits().maxPayloadBytes()).isEqualTo(ADVANCED_LIMITS.maxPayloadBytes());
        assertThat(manifest.limits().maxJoins()).isEqualTo(ADVANCED_COMPLEXITY_LIMITS.maxJoins());
        assertThat(manifest.limits().maxNestingDepth()).isEqualTo(ADVANCED_COMPLEXITY_LIMITS.maxNestingDepth());
    }

    // ADR 0033: explain-plan capture shipped as thread 2, replacing thread
    // 1 (ADR 0032)'s "stays false, that's a later thread" placeholder.
    @Test
    void capabilities_reportsExplainPlanTrueOnlyUnderAdvancedQuery() {
        CapabilityManifest manifest = newAdapter(CapabilityProfile.ADVANCED_QUERY).capabilities();

        assertThat(manifest.capabilities().explainPlan()).isTrue();
    }

    @Test
    void capabilities_reportsExplainPlanFalseUnderSafeQuery() {
        CapabilityManifest manifest = newAdapter(CapabilityProfile.SAFE_QUERY).capabilities();

        assertThat(manifest.capabilities().explainPlan()).isFalse();
    }

    @Test
    void capabilities_reportsExplainPlanFalseUnderMetadataOnly() {
        CapabilityManifest manifest = newAdapter(CapabilityProfile.METADATA_ONLY).capabilities();

        assertThat(manifest.capabilities().explainPlan()).isFalse();
    }

    // #94 (R2-P1-7, 2026-09-17 re-review): getCapabilities was the single
    // tool that did not follow the project's own traceability convention.

    @Test
    void capabilities_tagsItsRootSpanWithTheCorrelationId() {
        SimpleTracer tracer = new SimpleTracer();

        newAdapter(CapabilityProfile.SAFE_QUERY, new SimpleMeterRegistry(), tracer).capabilities();

        assertThat(tracer.getSpans())
                .filteredOn(span -> "mcp.tool getCapabilities".equals(span.getName()))
                .isNotEmpty()
                .allSatisfy(span -> assertThat(span.getTags()).containsKey("correlationId"));
    }

    @Test
    void capabilities_recordsTheResultByteMetric() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        newAdapter(CapabilityProfile.SAFE_QUERY, registry, new SimpleTracer()).capabilities();

        assertThat(registry.find("mcp.result.bytes").tag("tool", "getCapabilities").summary())
                .isNotNull();
    }

    @Test
    void capabilities_returnsTheSameCorrelationIdItTaggedOnTheSpan() {
        // #94: a correlation ID the client receives is only useful if it is
        // the same one the server recorded. Asserting the field is merely
        // non-null would pass even if the manifest carried a fresh UUID
        // unrelated to the audit line and span.
        SimpleTracer tracer = new SimpleTracer();

        CapabilityManifest manifest =
                newAdapter(CapabilityProfile.SAFE_QUERY, new SimpleMeterRegistry(), tracer).capabilities();

        assertThat(manifest.correlationId()).isNotBlank();
        assertThat(tracer.getSpans())
                .filteredOn(span -> "mcp.tool getCapabilities".equals(span.getName()))
                .isNotEmpty()
                .allSatisfy(span ->
                        assertThat(span.getTags()).containsEntry("correlationId", manifest.correlationId()));
    }

    @Test
    void capabilities_usesAFreshCorrelationIdPerCall() {
        var first = newAdapter(CapabilityProfile.SAFE_QUERY).capabilities();
        var second = newAdapter(CapabilityProfile.SAFE_QUERY).capabilities();

        assertThat(first.correlationId()).isNotEqualTo(second.correlationId());
    }
}
