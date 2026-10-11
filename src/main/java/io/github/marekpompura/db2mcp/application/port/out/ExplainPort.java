package io.github.marekpompura.db2mcp.application.port.out;

import io.github.marekpompura.db2mcp.domain.model.ExplainPlanResult;

/**
 * Outbound port for DB2 EXPLAIN-plan capture (ADR 0033, master brief
 * section 6.3 - "may support explain-plan experiments"). Deliberately NOT
 * part of {@link QueryPort}: DB2's EXPLAIN_STATEMENT/EXPLAIN_OPERATOR
 * catalog tables are DB2-specific machinery, in the same family as the
 * {@code SYSCAT.*} views ADR 0009 classifies as having "no substitute that
 * can verify this SQL actually runs correctly" - forcing a fake/portable
 * implementation onto this port (as {@link QueryPort}'s {@code
 * FakeDatabaseAdapter} implementation would require) would misrepresent a
 * fundamentally DB2-only capability as portability evidence.
 */
public interface ExplainPort {

    ExplainPlanResult explainQuery(String sql);
}
