package io.github.marekpompura.db2mcp.domain.model;

/**
 * specificName (SYSCAT.PROCEDURES.SPECIFICNAME) uniquely identifies one
 * overload when procedureName is ambiguous - DB2 allows multiple specific
 * routines sharing a schema and name with different signatures (CR-003,
 * 2026-08-14 code review, ADR 0022). Pass specificName, not procedureName,
 * to describeProcedure/getProcedureSource to select this exact overload.
 */
public record ProcedureInfo(String schemaName, String procedureName, String specificName, String language, int paramCount) {
}
