package io.github.marekpompura.db2mcp.domain.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * truncated is true when more rows existed than the server-side row cap
 * allowed through (ADR 0007). correlationId (P1-3, 2026-09-01 master-brief
 * gap review, #40, ADR 0041) is the same id the adapter's own audit log line
 * and every span for this call already carry - a successful call previously
 * had no way to hand the caller anything to correlate with server-side
 * records; only a failed one did, via {@code McpToolException}.
 */
public record QueryResult(List<QueryColumn> columns, List<List<Object>> rows, int rowCount, boolean truncated, String correlationId) {

    /**
     * Defensively copies {@code columns}/{@code rows} into unmodifiable
     * lists - without this, a caller holding the same list reference passed
     * into the constructor (e.g. {@code Db2QueryAdapter}'s own local
     * variable) could mutate what's supposed to be an immutable result
     * record after the fact. Caught by SpotBugs (issue #42, P1-5:
     * EI_EXPOSE_REP2) rather than assumed safe.
     */
    public QueryResult {
        columns = List.copyOf(columns);
        // Deep copy (#67, RR-P2-1 of the 2026-09-09 re-review): copying only
        // the outer list left every inner row as the caller's own mutable
        // list, so result.rows().get(0).add(...) still worked and this record
        // was immutable in name only.
        //
        // Deliberately not List.copyOf per row: a cell is null for SQL NULL
        // (see Db2ValueNormalizer), and List.copyOf rejects null elements -
        // that would turn every NULL-bearing result into a
        // NullPointerException, trading a mutability nit for a real outage.
        // The outer list goes through List.copyOf, not Stream.toList: both
        // are immutable, but SpotBugs recognises List.copyOf as a
        // known-immutable factory and cannot track immutability through a
        // stream, so the stream form makes rows() look like it leaks
        // internal state (EI_EXPOSE_REP). Its elements are lists, never
        // null, so List.copyOf is safe here - unlike inside a row.
        List<List<Object>> deeplyCopied = new ArrayList<>(rows.size());
        for (List<Object> row : rows) {
            deeplyCopied.add(Collections.unmodifiableList(new ArrayList<>(row)));
        }
        rows = List.copyOf(deeplyCopied);
    }
}
