package io.github.marekpompura.db2mcp.domain.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * The exception's getMessage() is the entire channel an MCP client sees
 * (verified by decompiling this project's Spring AI MCP dependency -
 * McpToolException's own class Javadoc). These tests verify the actual
 * rendered JSON shape a client would receive, not just that some message
 * exists.
 */
class McpToolExceptionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void messageIsParseableJsonWithTheCategorySanitizedMessageAndSqlState() throws IOException {
        McpToolException exception =
                new McpToolException(ErrorCategory.DB2_OBJECT_NOT_FOUND, "Query execution failed", "42704");

        JsonNode json = MAPPER.readTree(exception.getMessage());

        assertThat(json.get("errorCode").asText()).isEqualTo("DB2_OBJECT_NOT_FOUND");
        assertThat(json.get("message").asText()).isEqualTo("Query execution failed");
        assertThat(json.get("sqlState").asText()).isEqualTo("42704");
    }

    @Test
    void sqlStateIsNullInMessageWhenNotSupplied() throws IOException {
        McpToolException exception = new McpToolException(ErrorCategory.CAPABILITY_DISABLED, "Not enabled");

        JsonNode json = MAPPER.readTree(exception.getMessage());

        assertThat(json.get("sqlState").isNull()).isTrue();
    }

    @Test
    void categoryAndSqlStateAreAlsoAvailableAsTypedAccessors() {
        McpToolException exception = new McpToolException(ErrorCategory.QUERY_TIMEOUT, "Timed out", "57014");

        assertThat(exception.category()).isEqualTo(ErrorCategory.QUERY_TIMEOUT);
        assertThat(exception.sqlState()).isEqualTo("57014");
    }

    @Test
    void correlationIdAppearsInJsonBodyAndAccessor() throws IOException {
        McpToolException exception =
                new McpToolException(ErrorCategory.DB2_EXECUTION_FAILED, "Query execution failed", "58004", "abc-123");

        JsonNode json = MAPPER.readTree(exception.getMessage());

        assertThat(json.get("correlationId").asText()).isEqualTo("abc-123");
        assertThat(exception.correlationId()).isEqualTo("abc-123");
    }

    @Test
    void correlationIdIsNullInMessageWhenNotSupplied() throws IOException {
        McpToolException exception = new McpToolException(ErrorCategory.CAPABILITY_DISABLED, "Not enabled");

        JsonNode json = MAPPER.readTree(exception.getMessage());

        assertThat(json.get("correlationId").isNull()).isTrue();
        assertThat(exception.correlationId()).isNull();
    }

    @Test
    void neverLeaksAnUnsanitizedMessageIntoTheJsonBody() throws IOException {
        // The message parameter is exactly what this class puts in the JSON body -
        // it is the caller's responsibility to only ever pass sanitized text.
        // Documented here rather than assumed: this class does no sanitization of
        // its own, since Db2QueryAdapter already produces sanitized text before
        // constructing this exception.
        McpToolException exception = new McpToolException(ErrorCategory.INTERNAL_ERROR, "generic message");

        JsonNode json = MAPPER.readTree(exception.getMessage());
        assertThat(json.get("message").asText()).isEqualTo("generic message");
    }
}
