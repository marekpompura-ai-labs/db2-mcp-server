package io.github.marekpompura.db2mcp.mcp.tool.request;

import jakarta.validation.constraints.NotBlank;

/** {@link io.github.marekpompura.db2mcp.mcp.tool.QueryTools#executeQuery} (#39). */
public record ExecuteQueryRequest(@NotBlank String sql) {}
