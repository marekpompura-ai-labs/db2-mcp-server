package io.github.marekpompura.db2mcp.domain.model;

public record ColumnInfo(
        String columnName,
        String dataType,
        Integer length,
        Integer scale,
        boolean nullable,
        boolean primaryKey,
        int ordinalPosition) {
}
