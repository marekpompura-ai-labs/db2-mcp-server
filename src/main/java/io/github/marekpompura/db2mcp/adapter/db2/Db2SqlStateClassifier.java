package io.github.marekpompura.db2mcp.adapter.db2;

import io.github.marekpompura.db2mcp.domain.error.DatabaseErrorClassifier;
import io.github.marekpompura.db2mcp.domain.error.ErrorCategory;
import org.springframework.stereotype.Component;

/**
 * Maps a DB2 SQLSTATE to a stable {@link ErrorCategory} (master brief
 * section 11.4, ADR 0018), behind the database-neutral {@link
 * DatabaseErrorClassifier} port (database-portability extension section 10,
 * ADR 0027) - Db2-specific error-code knowledge belongs here, in the Db2
 * adapter, not in {@code domain.error}, which stays vendor-neutral.
 *
 * <p>{@code 57014} (query cancellation) is empirically verified, not just
 * documented - it's the exact SQLSTATE {@code
 * Db2QueryAdapterIntegrationTest.executeQuery_timesOutOnExpensiveCrossJoin}
 * observes from a real H2 connection when a JDBC statement timeout fires.
 * {@code 42501} and {@code 42704} are taken from DB2's own documented
 * SQLSTATE reference (insufficient privilege; undefined object), not
 * verified against a live DB2 instance in this environment - the same Tier
 * 2 gap ADR 0009 already documents for everything else that needs a real
 * DB2 catalog to exercise.
 */
@Component
public class Db2SqlStateClassifier implements DatabaseErrorClassifier {

    private static final String QUERY_TIMEOUT = "57014";
    private static final String AUTHORIZATION_FAILED = "42501";
    private static final String OBJECT_NOT_FOUND = "42704";

    @Override
    public ErrorCategory classify(String sqlState) {
        if (sqlState == null) {
            return ErrorCategory.DB2_EXECUTION_FAILED;
        }
        return switch (sqlState) {
            case QUERY_TIMEOUT -> ErrorCategory.QUERY_TIMEOUT;
            case AUTHORIZATION_FAILED -> ErrorCategory.DB2_AUTHORIZATION_FAILED;
            case OBJECT_NOT_FOUND -> ErrorCategory.DB2_OBJECT_NOT_FOUND;
            default -> ErrorCategory.DB2_EXECUTION_FAILED;
        };
    }
}
