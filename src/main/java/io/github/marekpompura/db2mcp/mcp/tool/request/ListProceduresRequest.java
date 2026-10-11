package io.github.marekpompura.db2mcp.mcp.tool.request;

import jakarta.validation.constraints.NotBlank;

/** {@link io.github.marekpompura.db2mcp.mcp.tool.SchemaTools#listProcedures} (#39). */
public record ListProceduresRequest(@NotBlank String schema) {}
