package io.github.marekpompura.db2mcp.domain.error;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The exception type every rejection or failure path in this server
 * eventually throws (master brief section 11.4, ADR 0018). Its
 * {@link #getMessage()} - rendered once, at construction, not computed lazily
 * - is a small JSON object ({@code errorCode}, {@code message}, {@code
 * sqlState}, {@code correlationId}), not free text, because that message is
 * the *entire* channel an MCP client sees: verified by decompiling this
 * project's exact Spring AI MCP dependency (0.10.0/1.0.0) rather than assumed
 * from documentation - {@code McpToolUtils.toSyncToolSpecification}'s handler
 * catches any {@code Exception} thrown from a {@code @Tool} method and builds
 * a {@code CallToolResult} whose only content is {@code TextContent(exception
 * .getMessage())} with {@code isError: true}. There is no separate channel
 * for a structured error code; this message body is it.
 *
 * <p>{@code correlationId} (CR-012, 2026-08-14 code review, ADR 0025) is the
 * same ID every tool already writes to its audit log line - a caller
 * reporting a failed tool call now has something to hand back for finding
 * the matching audit entry, rather than only a category and message with no
 * way to correlate to server-side records.
 */
public class McpToolException extends RuntimeException {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ErrorCategory category;
    private final String sqlState;
    private final String correlationId;

    public McpToolException(ErrorCategory category, String sanitizedMessage) {
        this(category, sanitizedMessage, null, null);
    }

    public McpToolException(ErrorCategory category, String sanitizedMessage, String sqlState) {
        this(category, sanitizedMessage, sqlState, null);
    }

    public McpToolException(ErrorCategory category, String sanitizedMessage, String sqlState, String correlationId) {
        super(render(category, sanitizedMessage, sqlState, correlationId));
        this.category = category;
        this.sqlState = sqlState;
        this.correlationId = correlationId;
    }

    public ErrorCategory category() {
        return category;
    }

    public String sqlState() {
        return sqlState;
    }

    public String correlationId() {
        return correlationId;
    }

    private static String render(ErrorCategory category, String message, String sqlState, String correlationId) {
        try {
            return MAPPER.writeValueAsString(new ErrorBody(category, message, sqlState, correlationId));
        } catch (JsonProcessingException e) {
            // Unreachable in practice - ErrorBody is an enum plus plain strings,
            // nothing Jackson can fail to serialize - but error reporting itself must
            // never be the thing that throws, so fall back to a plain-text rendering
            // rather than letting this constructor propagate a JSON exception.
            return category + ": " + message;
        }
    }

    private record ErrorBody(ErrorCategory errorCode, String message, String sqlState, String correlationId) {}
}
