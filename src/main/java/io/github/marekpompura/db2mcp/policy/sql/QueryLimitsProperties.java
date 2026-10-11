package io.github.marekpompura.db2mcp.policy.sql;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Resource limits for executeQuery (ADR 0007). Externalized from the
 * hardcoded constants in the original implementation so tests can exercise
 * timeout behavior without waiting on a real 30-second timeout.
 *
 * <p>All four fields are validated at startup (ADR 0015): {@code maxRows
 * <= 0} would make {@code Db2QueryAdapter}'s {@code rows.subList(0,
 * maxRows)} throw at query time instead of failing fast at boot, a
 * non-positive {@code timeoutSeconds} is meaningless for a JDBC query
 * timeout, and a non-positive {@code maxCellBytes}/{@code maxPayloadBytes}
 * would make every cell/result truncate to nothing.
 *
 * <p>{@code maxCellBytes} and {@code maxPayloadBytes} (CR-005, 2026-08-14
 * code review, ADR 0023) bound response size independently of {@code
 * maxRows}: a single row can still contain an arbitrarily large CLOB/BLOB
 * value, and {@code maxRows} alone does nothing about that. Default values
 * (16 KiB per cell, 1 MiB total) match the example limits the adopted
 * database-portability extension's capability manifest already showed
 * (ADR 0021), not picked independently.
 */
@Validated
@ConfigurationProperties(prefix = "db2mcp.query")
public record QueryLimitsProperties(
        @DefaultValue("1000") @Positive int maxRows,
        @DefaultValue("30") @Positive int timeoutSeconds,
        @DefaultValue("16384") @Positive int maxCellBytes,
        @DefaultValue("1048576") @Positive int maxPayloadBytes) {
}
