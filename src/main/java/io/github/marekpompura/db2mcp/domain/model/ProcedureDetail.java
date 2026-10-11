package io.github.marekpompura.db2mcp.domain.model;

import java.util.List;

public record ProcedureDetail(ProcedureInfo procedure, List<ProcedureParameterInfo> parameters) {

    /** Defensively copies {@code parameters} - see {@link QueryResult}'s own compact constructor for why (issue #42, P1-5: EI_EXPOSE_REP2). */
    public ProcedureDetail {
        parameters = List.copyOf(parameters);
    }
}
