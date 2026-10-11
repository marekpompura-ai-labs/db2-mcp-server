package io.github.marekpompura.db2mcp.mcp.tool;

import io.github.marekpompura.db2mcp.application.port.out.ExplainPort;
import io.github.marekpompura.db2mcp.domain.model.ExplainPlanResult;
import io.github.marekpompura.db2mcp.mcp.tool.request.ExplainQueryRequest;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Only registered under {@code ADVANCED_QUERY} (McpConfig, ADR 0033) - a
 * narrower gate than {@link QueryTools}/{@link ReferenceDataTools}'s "any
 * query-capable profile" (ADR 0006), since explain-plan capture is
 * explicitly an advanced-only capability per master brief section 6.3, not
 * something {@code SAFE_QUERY} grants.
 */
@Component
public class ExplainTools {

    private final ExplainPort explainPort;
    private final ToolInputValidator validator;

    public ExplainTools(ExplainPort explainPort, ToolInputValidator validator) {
        this.explainPort = explainPort;
        this.validator = validator;
    }

    @Tool(description = "Capture DB2's access plan for a read-only SELECT or WITH query, without executing it")
    public ExplainPlanResult explainQuery(
            @ToolParam(description = "SQL text, must start with SELECT or WITH") String sql) {
        validator.validate(new ExplainQueryRequest(sql));
        return explainPort.explainQuery(sql);
    }
}
