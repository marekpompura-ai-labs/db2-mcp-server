package io.github.marekpompura.db2mcp.domain.model;

/**
 * sourceText is null for non-SQL procedure languages (e.g. COBOL), which
 * have no source in the catalog. specificName identifies exactly which
 * overload this source came from (CR-003, ADR 0022).
 */
public record ProcedureSource(String schemaName, String procedureName, String specificName, String sourceText) {
}
