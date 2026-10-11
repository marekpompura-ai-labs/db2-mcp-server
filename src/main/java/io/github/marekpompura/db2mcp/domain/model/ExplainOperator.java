package io.github.marekpompura.db2mcp.domain.model;

/** One row of DB2's EXPLAIN_OPERATOR table for a captured plan (ADR 0033). */
public record ExplainOperator(int operatorId, String operatorType, double totalCost) {
}
