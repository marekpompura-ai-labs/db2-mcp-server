package io.github.marekpompura.db2mcp.policy.sql;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.marekpompura.db2mcp.policy.capability.CapabilityProfile;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ResolvedQueryLimitsTest {

    private static final QueryLimitsProperties BASE = new QueryLimitsProperties(1000, 30, 16384, 1048576);
    private static final AdvancedQueryLimitsProperties ADVANCED =
            new AdvancedQueryLimitsProperties(5000, 120, 65536, 4194304);

    @ParameterizedTest
    @CsvSource({"METADATA_ONLY", "SAFE_QUERY"})
    void of_usesBaseLimitsForNonAdvancedProfiles(CapabilityProfile profile) {
        ResolvedQueryLimits resolved = ResolvedQueryLimits.of(profile, BASE, ADVANCED);

        assertThat(resolved.maxRows()).isEqualTo(BASE.maxRows());
        assertThat(resolved.timeoutSeconds()).isEqualTo(BASE.timeoutSeconds());
        assertThat(resolved.maxCellBytes()).isEqualTo(BASE.maxCellBytes());
        assertThat(resolved.maxPayloadBytes()).isEqualTo(BASE.maxPayloadBytes());
    }

    @org.junit.jupiter.api.Test
    void of_usesAdvancedLimitsForAdvancedQueryProfile() {
        ResolvedQueryLimits resolved = ResolvedQueryLimits.of(CapabilityProfile.ADVANCED_QUERY, BASE, ADVANCED);

        assertThat(resolved.maxRows()).isEqualTo(ADVANCED.maxRows());
        assertThat(resolved.timeoutSeconds()).isEqualTo(ADVANCED.timeoutSeconds());
        assertThat(resolved.maxCellBytes()).isEqualTo(ADVANCED.maxCellBytes());
        assertThat(resolved.maxPayloadBytes()).isEqualTo(ADVANCED.maxPayloadBytes());
    }
}
