package io.github.marekpompura.db2mcp.domain.model;

/**
 * Identifies the connected database (database-portability extension section
 * 5.1, ADR 0028). Never exposes hostname, port, username, JDBC URL, or any
 * other connection/topology detail - only {@code product}, {@code
 * productVersion} (queried live from the JDBC driver, not hardcoded - see
 * {@code Db2CapabilityAdapter}), {@code sqlDialect}, and {@code
 * identifierRules}, none of which can identify a specific deployment.
 */
public record DatabaseDescriptor(
        DatabaseProduct product, String productVersion, String sqlDialect, IdentifierRules identifierRules) {
}
