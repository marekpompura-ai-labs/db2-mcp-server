package io.github.marekpompura.db2mcp.mcp.tool;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.marekpompura.db2mcp.application.port.out.ExplainPort;
import io.github.marekpompura.db2mcp.domain.error.McpToolException;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

/** #39 acceptance evidence: {@code explainQuery} now rejects a blank/null sql before ever reaching {@link ExplainPort}. */
class ExplainToolsTest {

    private final ExplainPort explainPort = mock(ExplainPort.class);
    private final ExplainTools explainTools = new ExplainTools(
            explainPort, new ToolInputValidator(Validation.buildDefaultValidatorFactory().getValidator()));

    @Test
    void explainQuery_blankSql_rejectedBeforeReachingThePort() {
        assertThatThrownBy(() -> explainTools.explainQuery("   ")).isInstanceOf(McpToolException.class);

        verify(explainPort, never()).explainQuery(anyString());
    }

    @Test
    void explainQuery_nullSql_rejectedBeforeReachingThePort() {
        assertThatThrownBy(() -> explainTools.explainQuery(null)).isInstanceOf(McpToolException.class);

        verify(explainPort, never()).explainQuery(anyString());
    }

    @Test
    void explainQuery_nonBlankSql_delegatesToThePort() {
        explainTools.explainQuery("SELECT 1 FROM SYSIBM.SYSDUMMY1");

        verify(explainPort).explainQuery("SELECT 1 FROM SYSIBM.SYSDUMMY1");
    }
}
