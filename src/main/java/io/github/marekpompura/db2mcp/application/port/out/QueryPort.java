package io.github.marekpompura.db2mcp.application.port.out;

import io.github.marekpompura.db2mcp.domain.model.QueryResult;

/**
 * Outbound port for guarded, read-only ad-hoc SQL execution
 * (database-portability extension section 7.1, ADR 0027). Kept separate
 * from {@link MetadataPort}: this is arbitrary caller-supplied SQL under a
 * guard (ADR 0002 layer 3), not a structurally validated lookup.
 */
public interface QueryPort {

    QueryResult executeQuery(String sql);
}
