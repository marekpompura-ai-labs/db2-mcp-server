package io.github.marekpompura.db2mcp.mcp.tool.request;

import jakarta.validation.constraints.NotBlank;

/** {@link io.github.marekpompura.db2mcp.mcp.tool.SchemaTools#getTableDependencies} (#39). */
public record GetTableDependenciesRequest(@NotBlank String schema, @NotBlank String table) {}
