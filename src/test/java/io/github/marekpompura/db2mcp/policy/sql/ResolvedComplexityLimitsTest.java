package io.github.marekpompura.db2mcp.policy.sql;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.marekpompura.db2mcp.policy.capability.CapabilityProfile;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ResolvedComplexityLimitsTest {

    private static final QueryComplexityLimitsProperties BASE = new QueryComplexityLimitsProperties(5, 3);
    private static final AdvancedQueryComplexityLimitsProperties ADVANCED =
            new AdvancedQueryComplexityLimitsProperties(15, 6);

    @ParameterizedTest
    @CsvSource({"METADATA_ONLY", "SAFE_QUERY"})
    void of_usesBaseLimitsForNonAdvancedProfiles(CapabilityProfile profile) {
        ResolvedComplexityLimits resolved = ResolvedComplexityLimits.of(profile, BASE, ADVANCED);

        assertThat(resolved.maxJoins()).isEqualTo(BASE.maxJoins());
        assertThat(resolved.maxNestingDepth()).isEqualTo(BASE.maxNestingDepth());
    }

    @org.junit.jupiter.api.Test
    void of_usesAdvancedLimitsForAdvancedQueryProfile() {
        ResolvedComplexityLimits resolved = ResolvedComplexityLimits.of(CapabilityProfile.ADVANCED_QUERY, BASE, ADVANCED);

        assertThat(resolved.maxJoins()).isEqualTo(ADVANCED.maxJoins());
        assertThat(resolved.maxNestingDepth()).isEqualTo(ADVANCED.maxNestingDepth());
    }
}
