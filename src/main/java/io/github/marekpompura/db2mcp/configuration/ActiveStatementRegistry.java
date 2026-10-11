package io.github.marekpompura.db2mcp.configuration;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Tracks every currently-executing JDBC {@link Statement} so a disconnected
 * MCP client's in-flight query can be cancelled instead of left to run to
 * completion or its own statement timeout in the background (issue #46,
 * 2026-09-01 master-brief gap review, P2-2).
 *
 * <p>{@link StdioLifecycleConfig#awaitDrainThenShutdown} is this registry's
 * only intended caller of {@link #cancelAll()} - only once {@link
 * InFlightRequestTracker#awaitDrain} has already timed out waiting for an
 * in-flight MCP request's response, i.e. only once ADR 0031's own
 * wait-for-the-response-to-arrive behavior has already given the query its
 * full grace period. Cancelling any earlier would reintroduce the exact
 * problem ADR 0031 fixed - a query a real client is still waiting on,
 * killed before it had a fair chance to finish.
 *
 * <p>Deliberately keyed by the {@link Statement} instance itself, not by
 * MCP request id or {@code correlationId} - {@link
 * io.github.marekpompura.db2mcp.adapter.db2.Db2QueryAdapter} never learns
 * the JSON-RPC request id a tool call is answering (that context lives
 * entirely in the transport layer, ADR 0001's own separation), and at
 * shutdown time this registry's only job is "cancel whatever is still
 * running," not "cancel this one specific query" - correlating by id would
 * be real complexity spent on a distinction shutdown-time cancellation
 * never needs.
 */
@Component
public class ActiveStatementRegistry {

    private static final Logger log = LoggerFactory.getLogger(ActiveStatementRegistry.class);

    private final Set<Statement> active = ConcurrentHashMap.newKeySet();
    private final Set<Statement> cancelled = ConcurrentHashMap.newKeySet();

    public void register(Statement statement) {
        active.add(statement);
    }

    /** Mirrors {@link InFlightRequestTracker#current()}'s same observability/testability purpose. */
    public int activeCount() {
        return active.size();
    }

    /**
     * Must be called from a {@code finally} block around the statement's
     * execution, regardless of outcome - otherwise a completed statement
     * would stay registered (and thus a candidate for a future, meaningless
     * {@link #cancelAll()} call) forever.
     */
    public void deregister(Statement statement) {
        active.remove(statement);
        cancelled.remove(statement);
    }

    /**
     * True only if {@link #cancelAll()} itself cancelled this exact
     * statement - not true for a statement that failed for any other
     * reason, including hitting its own JDBC statement timeout on its own
     * (same SQLSTATE as an explicit cancel on most drivers, including H2
     * and DB2, so this flag - not the SQLSTATE - is what actually
     * distinguishes the two for the audit trail).
     */
    public boolean wasCancelled(Statement statement) {
        return cancelled.contains(statement);
    }

    /**
     * Cancels every currently-registered statement. A single statement
     * failing to cancel (e.g. the underlying driver/connection is already
     * gone) is logged and does not stop the rest from being attempted.
     *
     * @return how many statements were successfully cancelled
     */
    public int cancelAll() {
        int cancelledCount = 0;
        for (Statement statement : active) {
            try {
                statement.cancel();
                cancelled.add(statement);
                cancelledCount++;
            } catch (SQLException e) {
                log.warn("Failed to cancel an in-flight DB2 statement during shutdown", e);
            }
        }
        return cancelledCount;
    }
}
