package io.github.marekpompura.db2mcp.mcp.tool.request;

import jakarta.validation.constraints.NotBlank;

/** {@link io.github.marekpompura.db2mcp.mcp.tool.SchemaTools#getProcedureSource} (#39). */
public record GetProcedureSourceRequest(@NotBlank String schema, @NotBlank String specificName) {}
