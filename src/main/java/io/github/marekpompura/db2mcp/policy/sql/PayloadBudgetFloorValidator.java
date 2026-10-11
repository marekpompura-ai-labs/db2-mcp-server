package io.github.marekpompura.db2mcp.policy.sql;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Fails startup fast (ADR 0015's pattern) if a configured {@code
 * maxPayloadBytes} is too small for any response to fit inside it.
 *
 * <p>{@code @Positive} alone stopped being a sufficient constraint once the
 * payload bound began failing closed (#62, ADR 0045). Every response now
 * carries a fixed envelope - for {@code BoundedList} that is the {@code
 * items}/{@code rowCount}/{@code truncated} keys plus a 36-character
 * correlation ID, around 100 bytes before a single item - and a budget
 * below that admits nothing at all. The server would start happily and
 * then fail <em>every</em> tool call with {@code RESULT_LIMIT_EXCEEDED},
 * which is a slow and confusing way to discover a mistyped setting.
 *
 * <p>The floor is deliberately coarse. It cannot be exact, because {@code
 * QueryResult}'s envelope grows with the column count of whatever query is
 * run, so the true minimum is query-dependent and unknowable at startup.
 * What this guarantees is that the fixed part of an envelope always fits,
 * which is enough to separate a typo from deliberate tuning - a genuinely
 * small-but-workable budget is left alone.
 */
@Component
public class PayloadBudgetFloorValidator implements ApplicationRunner {

    /**
     * Comfortably above the ~100-byte fixed envelope, while still far below
     * any budget a deployment would choose on purpose.
     */
    static final int MINIMUM_PAYLOAD_BYTES = 1024;

    private final QueryLimitsProperties queryLimits;
    private final AdvancedQueryLimitsProperties advancedQueryLimits;

    public PayloadBudgetFloorValidator(
            QueryLimitsProperties queryLimits, AdvancedQueryLimitsProperties advancedQueryLimits) {
        this.queryLimits = queryLimits;
        this.advancedQueryLimits = advancedQueryLimits;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Both profiles are checked regardless of which one is active: a
        // profile switch should not be able to turn a dormant typo into a
        // server that answers nothing.
        check("db2mcp.query-limits.safe-query.max-payload-bytes", queryLimits.maxPayloadBytes());
        check("db2mcp.query-limits.advanced-query.max-payload-bytes", advancedQueryLimits.maxPayloadBytes());
    }

    private static void check(String property, int configured) {
        if (configured < MINIMUM_PAYLOAD_BYTES) {
            throw new PayloadBudgetTooSmallException(property, configured);
        }
    }

    /** Startup failure: a payload budget no response could ever fit into. */
    public static final class PayloadBudgetTooSmallException extends RuntimeException {
        PayloadBudgetTooSmallException(String property, int configured) {
            super(property + " is " + configured + " bytes, below the " + MINIMUM_PAYLOAD_BYTES
                    + "-byte minimum: no response envelope fits in it, so every tool call would fail with"
                    + " RESULT_LIMIT_EXCEEDED. See ADR 0045.");
        }
    }
}
