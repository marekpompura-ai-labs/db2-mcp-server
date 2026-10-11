package io.github.marekpompura.db2mcp.mcp.tool.request;

import jakarta.validation.constraints.NotBlank;

/** {@link io.github.marekpompura.db2mcp.mcp.tool.ExplainTools#explainQuery} (#39). */
public record ExplainQueryRequest(@NotBlank String sql) {}
