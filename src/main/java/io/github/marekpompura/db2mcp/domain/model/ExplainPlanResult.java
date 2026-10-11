package io.github.marekpompura.db2mcp.domain.model;

import java.util.List;

/**
 * The operator-level shape of a DB2 access plan (ADR 0033) - read back from
 * EXPLAIN_STATEMENT/EXPLAIN_OPERATOR after {@code EXPLAIN PLAN FOR
 * <statement>}, not the statement's actual result data (nothing is
 * executed). {@code truncated} is true when more operators existed than the
 * server-side cap allowed through, mirroring {@link QueryResult}'s own
 * truncation convention (ADR 0007). {@code correlationId} (P1-3, 2026-09-01
 * master-brief gap review, #40, ADR 0041) mirrors {@link QueryResult}'s own
 * addition of the same field, for the same reason.
 *
 * @param statementText the caller's own SQL, returned verbatim - unlike the
 *     audit path (which only ever records {@code sqlFingerprint.of(sql)}),
 *     deliberately not redacted or fingerprinted here. {@code explainQuery}
 *     exists to show the caller their own query's plan back to them; there
 *     is no confidentiality boundary crossed by echoing text the caller
 *     themselves supplied in the same request. Flagged by the 2026-09-01
 *     master-brief gap review (P1-4, issue #41) as worth an explicit
 *     decision rather than leaving it as an unexamined side effect - this
 *     is that decision, not an oversight.
 */
public record ExplainPlanResult(String statementText, List<ExplainOperator> operators, boolean truncated, String correlationId) {

    /** Defensively copies {@code operators} - see {@link QueryResult}'s own compact constructor for why (issue #42, P1-5: EI_EXPOSE_REP2). */
    public ExplainPlanResult {
        operators = List.copyOf(operators);
    }
}
