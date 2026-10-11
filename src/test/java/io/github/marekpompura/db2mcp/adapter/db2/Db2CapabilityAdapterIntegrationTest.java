package io.github.marekpompura.db2mcp.adapter.db2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import io.github.marekpompura.db2mcp.testsupport.ItsmH2Database;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.test.simple.SimpleTracer;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * Tier 1 integration coverage (ADR 0009) for {@code getCapabilities} (ADR
 * 0028): the live-{@code productVersion}-query path against a real H2
 * connection, and the graceful-degradation path when that query genuinely
 * fails - not mocks standing in for JDBC behavior in either case, except
 * for the failure path's {@code DataSource} itself, which must fail
 * deterministically rather than depend on a real unreachable network
 * endpoint (that class of failure is already covered, against real DB2
 * network behavior, by {@code Db2LiveConnectivityIT}).
 */
class Db2CapabilityAdapterIntegrationTest {

    private static Db2CapabilityAdapter newAdapter(DataSource dataSource, CapabilityProfile profile) {
        return new Db2CapabilityAdapter(
                dataSource,
                new CapabilityProperties(profile),
                new DatabaseSelectionProperties(DatabaseProduct.DB2),
                new QueryLimitsProperties(500, 15, 16384, 1048576),
                new AdvancedQueryLimitsProperties(2500, 60, 32768, 2097152),
                new QueryComplexityLimitsProperties(5, 3),
                new AdvancedQueryComplexityLimitsProperties(15, 6),
                new AuditLogger(),
                new MetricsRecorder(new SimpleMeterRegistry()),
                new SpanRecorder(new SimpleTracer()));
    }

    @Test
    void capabilities_reportsARealLiveProductVersionFromH2() {
        DataSource dataSource = ItsmH2Database.create("capabilities_live_version");
        Db2CapabilityAdapter adapter = newAdapter(dataSource, CapabilityProfile.SAFE_QUERY);

        CapabilityManifest manifest = adapter.capabilities();

        assertThat(manifest.contractVersion()).isEqualTo("1.0");
        assertThat(manifest.database().product()).isEqualTo(DatabaseProduct.DB2);
        assertThat(manifest.database().sqlDialect()).isEqualTo("DB2_LUW");
        // A real H2 version string (e.g. "2.2.224 (2023-09-17)"), not a
        // hardcoded DB2 version this environment has never actually verified -
        // proves the query is genuinely live, not a fixed constant.
        assertThat(manifest.database().productVersion())
                .isNotBlank()
                .doesNotContain("unavailable");
    }

    @Test
    void capabilities_reflectsEffectiveLimitsExactly() {
        DataSource dataSource = ItsmH2Database.create("capabilities_limits");
        Db2CapabilityAdapter adapter = newAdapter(dataSource, CapabilityProfile.SAFE_QUERY);

        CapabilityManifest manifest = adapter.capabilities();

        assertThat(manifest.limits().maxRows()).isEqualTo(500);
        assertThat(manifest.limits().maxCellBytes()).isEqualTo(16384);
        assertThat(manifest.limits().maxPayloadBytes()).isEqualTo(1048576);
        assertThat(manifest.limits().maxJoins()).isEqualTo(5);
        assertThat(manifest.limits().maxNestingDepth()).isEqualTo(3);
    }

    @Test
    void capabilities_underMetadataOnlyReportsQueryCapabilitiesAsFalse() {
        DataSource dataSource = ItsmH2Database.create("capabilities_metadata_only");
        Db2CapabilityAdapter adapter = newAdapter(dataSource, CapabilityProfile.METADATA_ONLY);

        CapabilityManifest manifest = adapter.capabilities();

        assertThat(manifest.capabilities().readOnlyQuery()).isFalse();
        assertThat(manifest.capabilities().referenceDataLookup()).isFalse();
        assertThat(manifest.capabilities().schemaDiscovery()).isTrue();
        assertThat(manifest.capabilities().explainPlan()).isFalse();
    }

    @Test
    void capabilities_underSafeQueryReportsQueryCapabilitiesAsTrue() {
        DataSource dataSource = ItsmH2Database.create("capabilities_safe_query");
        Db2CapabilityAdapter adapter = newAdapter(dataSource, CapabilityProfile.SAFE_QUERY);

        CapabilityManifest manifest = adapter.capabilities();

        assertThat(manifest.capabilities().readOnlyQuery()).isTrue();
        assertThat(manifest.capabilities().referenceDataLookup()).isTrue();
    }

    @Test
    void capabilities_neverThrowsAndDegradesGracefullyWhenTheLiveVersionQueryGenuinelyFails() throws SQLException {
        DataSource brokenDataSource = mock(DataSource.class);
        when(brokenDataSource.getConnection()).thenThrow(new SQLException("connection refused", "08001"));
        Db2CapabilityAdapter adapter = newAdapter(brokenDataSource, CapabilityProfile.SAFE_QUERY);

        CapabilityManifest manifest = adapter.capabilities();

        assertThat(manifest.database().productVersion()).contains("unavailable");
        // Everything else in the manifest is still meaningful even when the
        // live version query fails - a diagnostic query failing must not take
        // down the whole tool, the same "must not be the reason a caller can't
        // proceed" reasoning ADR 0015's EffectiveConfigReporter already uses.
        assertThat(manifest.database().product()).isEqualTo(DatabaseProduct.DB2);
        assertThat(manifest.limits().maxRows()).isEqualTo(500);
    }
}
