package io.github.marekpompura.db2mcp.domain.model;

/**
 * One row per (constraint, column) pair. For composite foreign keys, multiple
 * rows share the same constraintName - kept flat rather than aggregated to
 * avoid depending on DB2's LISTAGG availability/behavior, which hasn't been
 * verified against a live instance.
 */
public record TableDependencyInfo(
        String constraintName,
        String direction,
        String fkSchema,
        String fkTable,
        String fkColumn,
        String pkSchema,
        String pkTable,
        String pkColumn) {
}
