package io.github.marekpompura.db2mcp.domain.model;

import java.util.List;

/**
 * The complete, size-bounded response envelope every list-producing tool
 * returns (#62, 2026-09-09 ChatGPT re-review, RR-P0-1).
 *
 * <p>Before this record, the metadata tools returned a bare {@code
 * List<T>}: a caller receiving 500 tables had no way to tell a complete
 * answer from one the server had silently cut, and {@code maxPayloadBytes}
 * was only ever a row-content budget rather than a bound on the response
 * actually sent. Both halves of that are the same defect - a bound is only
 * meaningful if the party on the other side is told when it bit.
 *
 * <p>{@code rowCount} is the number of items actually in {@code items},
 * never the pre-truncation total: the server deliberately does not report a
 * count it would have to keep reading past its own cap to learn. {@code
 * truncated} is true when either the row cap or the payload-byte budget
 * dropped something. {@code correlationId} matches the audit line and spans
 * for the same call, mirroring {@link QueryResult}'s own field (ADR 0041).
 */
public record BoundedList<T>(List<T> items, int rowCount, boolean truncated, String correlationId) {

    /** Defensively copies {@code items} - see {@link QueryResult}'s own compact constructor for why (issue #42, P1-5: EI_EXPOSE_REP2). */
    public BoundedList {
        items = List.copyOf(items);
    }

    public static <T> BoundedList<T> of(List<T> items, boolean truncated, String correlationId) {
        return new BoundedList<>(items, items.size(), truncated, correlationId);
    }
}
