package io.github.marekpompura.db2mcp.policy.capability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CapabilityProfileTest {

    @ParameterizedTest
    @CsvSource({"METADATA_ONLY,false", "SAFE_QUERY,true", "ADVANCED_QUERY,true"})
    void allowsQueryExecution_onlyMetadataOnlyIsRestricted(CapabilityProfile profile, boolean expected) {
        assertThat(profile.allowsQueryExecution()).isEqualTo(expected);
    }

    // Moved from Db2QueryAdapterTest (ADR 0032): the check itself moved off
    // Db2QueryAdapter's private static onto CapabilityProfile, so both
    // executeQuery and lookupRefData can share one implementation.
    @ParameterizedTest
    @CsvSource({"SAFE_QUERY", "ADVANCED_QUERY"})
    void requireQueryExecutionAllowed_allowsSafeAndAdvancedQuery(CapabilityProfile profile) {
        assertThatCode(() -> profile.requireQueryExecutionAllowed("executeQuery")).doesNotThrowAnyException();
    }

    @Test
    void requireQueryExecutionAllowed_rejectsMetadataOnly() {
        assertThatThrownBy(() -> CapabilityProfile.METADATA_ONLY.requireQueryExecutionAllowed("executeQuery"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("executeQuery")
                .hasMessageContaining("METADATA_ONLY");
    }

    @Test
    void requireQueryExecutionAllowed_messageNamesTheCallingTool() {
        assertThatThrownBy(() -> CapabilityProfile.METADATA_ONLY.requireQueryExecutionAllowed("lookupRefData"))
                .hasMessageContaining("lookupRefData");
    }

    // ADR 0033: explainQuery's gate is narrower than executeQuery's/
    // lookupRefData's - ADVANCED_QUERY only, not SAFE_QUERY too.
    @Test
    void requireExplainPlanAllowed_allowsAdvancedQuery() {
        assertThatCode(() -> CapabilityProfile.ADVANCED_QUERY.requireExplainPlanAllowed("explainQuery"))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource({"METADATA_ONLY", "SAFE_QUERY"})
    void requireExplainPlanAllowed_rejectsEverythingButAdvancedQuery(CapabilityProfile profile) {
        assertThatThrownBy(() -> profile.requireExplainPlanAllowed("explainQuery"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("explainQuery")
                .hasMessageContaining(profile.name())
                .hasMessageContaining("requires ADVANCED_QUERY");
    }
}
