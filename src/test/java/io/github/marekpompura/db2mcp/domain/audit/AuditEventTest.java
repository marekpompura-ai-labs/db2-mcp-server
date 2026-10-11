package io.github.marekpompura.db2mcp.domain.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

/**
 * Regression coverage for a real bug found while extending this class's
 * instrumentation pattern for {@code getCapabilities} (ADR 0028): a prior
 * version of {@link AuditEvent#allowed} took primitive {@code int}/
 * {@code boolean} parameters, which compiled fine but threw a {@code
 * NullPointerException} on unboxing whenever a caller legitimately had no
 * row count to report - exactly the case for {@code
 * Db2MetadataAdapter.describeProcedure}/{@code getProcedureSource}, whose
 * successful (ALLOWED) outcome has no {@code List} result to size.
 */
class AuditEventTest {

    @Test
    void allowedAcceptsNullRowCountAndTruncatedWithoutThrowing() {
        assertThatCode(() -> AuditEvent.allowed("cid", "describeProcedure", "PROCEDURE[X.Y]", 5L, null, null))
                .doesNotThrowAnyException();
    }

    @Test
    void allowedWithNullRowCountAndTruncatedProducesNullFields() {
        AuditEvent event = AuditEvent.allowed("cid", "describeProcedure", "PROCEDURE[X.Y]", 5L, null, null);

        assertThat(event.rowCount()).isNull();
        assertThat(event.truncated()).isNull();
        assertThat(event.decision()).isEqualTo(PolicyDecisionReason.ALLOWED);
    }

    @Test
    void allowedStillCarriesARealRowCountWhenOneExists() {
        AuditEvent event = AuditEvent.allowed("cid", "listSchemas", "SCHEMAS[]", 5L, 3, false);

        assertThat(event.rowCount()).isEqualTo(3);
        assertThat(event.truncated()).isFalse();
    }
}
