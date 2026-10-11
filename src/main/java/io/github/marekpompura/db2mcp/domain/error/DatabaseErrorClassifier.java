package io.github.marekpompura.db2mcp.domain.error;

/**
 * Maps a database-vendor-specific error code to a stable {@link
 * ErrorCategory} (database-portability extension section 10, ADR 0027).
 *
 * <p>The domain layer declares only this contract - it has no knowledge of
 * SQLSTATE, Oracle error numbers, or any other vendor's error-code shape.
 * Each database adapter supplies its own implementation ({@code
 * adapter.db2.Db2SqlStateClassifier} classifies DB2 SQLSTATE values today);
 * a future non-Db2 adapter would classify that vendor's own codes behind
 * this same interface, never inside {@code domain}.
 */
public interface DatabaseErrorClassifier {

    ErrorCategory classify(String vendorErrorCode);
}
