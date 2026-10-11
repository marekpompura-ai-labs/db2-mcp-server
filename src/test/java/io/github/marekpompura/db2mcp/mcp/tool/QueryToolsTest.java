package io.github.marekpompura.db2mcp.mcp.tool;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.marekpompura.db2mcp.application.port.out.QueryPort;
import io.github.marekpompura.db2mcp.domain.error.McpToolException;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

/** #39 acceptance evidence: {@code executeQuery} now rejects a blank/null sql before ever reaching {@link QueryPort}. */
class QueryToolsTest {

    private final QueryPort queryPort = mock(QueryPort.class);
    private final QueryTools queryTools = new QueryTools(
            queryPort, new ToolInputValidator(Validation.buildDefaultValidatorFactory().getValidator()));

    @Test
    void executeQuery_blankSql_rejectedBeforeReachingThePort() {
        assertThatThrownBy(() -> queryTools.executeQuery("   ")).isInstanceOf(McpToolException.class);

        verify(queryPort, never()).executeQuery(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void executeQuery_nullSql_rejectedBeforeReachingThePort() {
        assertThatThrownBy(() -> queryTools.executeQuery(null)).isInstanceOf(McpToolException.class);

        verify(queryPort, never()).executeQuery(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void executeQuery_nonBlankSql_delegatesToThePort() {
        queryTools.executeQuery("SELECT 1 FROM SYSIBM.SYSDUMMY1");

        verify(queryPort).executeQuery("SELECT 1 FROM SYSIBM.SYSDUMMY1");
    }
}
