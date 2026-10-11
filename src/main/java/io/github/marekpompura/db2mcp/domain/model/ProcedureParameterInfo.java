package io.github.marekpompura.db2mcp.domain.model;

/** parameterMode is DB2's SYSCAT.PROCPARMS.PARM_MODE: IN, OUT, or INOUT. */
public record ProcedureParameterInfo(String parameterName, String dataType, String parameterMode, int ordinalPosition) {
}
