package io.github.marekpompura.db2mcp.policy.sql;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * #62 (ADR 0045): once the payload bound began failing closed, {@code
 * @Positive} stopped being a sufficient constraint - a budget below the
 * fixed envelope size makes every tool call fail at runtime rather than
 * returning a small result, which is a slow way to discover a typo.
 */
class PayloadBudgetFloorValidatorTest {

    private static QueryLimitsProperties safeQuery(int maxPayloadBytes) {
        return new QueryLimitsProperties(1000, 30, 16384, maxPayloadBytes);
    }

    private static AdvancedQueryLimitsProperties advancedQuery(int maxPayloadBytes) {
        return new AdvancedQueryLimitsProperties(5000, 120, 65536, maxPayloadBytes);
    }

    private static void validate(QueryLimitsProperties safe, AdvancedQueryLimitsProperties advanced) {
        new PayloadBudgetFloorValidator(safe, advanced).run(null);
    }

    @Test
    void acceptsTheShippedDefaults() {
        assertThatCode(() -> validate(safeQuery(1048576), advancedQuery(4194304))).doesNotThrowAnyException();
    }

    @Test
    void acceptsASmallButWorkableBudget() {
        // Deliberately not a round production number: genuine tuning down to
        // a small budget stays allowed, only unusable values are rejected.
        assertThatCode(() -> validate(safeQuery(2048), advancedQuery(2048))).doesNotThrowAnyException();
    }

    @Test
    void rejectsASafeQueryBudgetNoEnvelopeCouldFitIn() {
        assertThatThrownBy(() -> validate(safeQuery(1), advancedQuery(4194304)))
                .isInstanceOf(PayloadBudgetFloorValidator.PayloadBudgetTooSmallException.class)
                .hasMessageContaining("safe-query.max-payload-bytes");
    }

    @Test
    void rejectsAnAdvancedQueryBudgetEvenWhenSafeQueryIsFine() {
        // Both profiles are checked regardless of which is active, so a
        // profile switch cannot turn a dormant typo into a server that
        // answers nothing.
        assertThatThrownBy(() -> validate(safeQuery(1048576), advancedQuery(64)))
                .isInstanceOf(PayloadBudgetFloorValidator.PayloadBudgetTooSmallException.class)
                .hasMessageContaining("advanced-query.max-payload-bytes");
    }

    @Test
    void theFloorIsAboveTheFixedEnvelopeItProtects() {
        // Guards the constant itself: an empty BoundedList envelope with a
        // 36-character correlation ID is around 100 bytes, so a floor at or
        // below that would not actually prevent the failure mode.
        assertThatThrownBy(() -> validate(safeQuery(100), advancedQuery(4194304)))
                .isInstanceOf(PayloadBudgetFloorValidator.PayloadBudgetTooSmallException.class);
    }
}
