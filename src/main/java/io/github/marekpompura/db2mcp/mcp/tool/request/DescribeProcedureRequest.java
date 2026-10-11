package io.github.marekpompura.db2mcp.mcp.tool.request;

import jakarta.validation.constraints.NotBlank;

/** {@link io.github.marekpompura.db2mcp.mcp.tool.SchemaTools#describeProcedure} (#39). */
public record DescribeProcedureRequest(@NotBlank String schema, @NotBlank String specificName) {}
