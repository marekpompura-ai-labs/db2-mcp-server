package io.github.marekpompura.db2mcp.mcp.tool.request;

import jakarta.validation.constraints.NotBlank;

/** {@link io.github.marekpompura.db2mcp.mcp.tool.SchemaTools#describeTable} (#39). */
public record DescribeTableRequest(@NotBlank String schema, @NotBlank String table) {}
