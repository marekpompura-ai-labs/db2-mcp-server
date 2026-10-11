package io.github.marekpompura.db2mcp.mcp.tool;

import io.github.marekpompura.db2mcp.application.port.out.QueryPort;
import io.github.marekpompura.db2mcp.domain.model.QueryResult;
import io.github.marekpompura.db2mcp.mcp.tool.request.ExecuteQueryRequest;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class QueryTools {

    private final QueryPort queryPort;
    private final ToolInputValidator validator;

    public QueryTools(QueryPort queryPort, ToolInputValidator validator) {
        this.queryPort = queryPort;
        this.validator = validator;
    }

    @Tool(description = "Execute a read-only SELECT or WITH query")
    public QueryResult executeQuery(@ToolParam(description = "SQL text, must start with SELECT or WITH") String sql) {
        validator.validate(new ExecuteQueryRequest(sql));
        return queryPort.executeQuery(sql);
    }
}
