package io.github.marekpompura.db2mcp.mcp.tool.request;

import jakarta.validation.constraints.NotBlank;

/**
 * {@link io.github.marekpompura.db2mcp.mcp.tool.ReferenceDataTools#lookupRefData}
 * (#39). {@code filterColumn}/{@code filterValue} are deliberately unvalidated
 * here - both are optional (a null pair means "no filter"), an existing,
 * exercised call shape ({@code FakeAdapterMcpToolsTest}), and this issue's
 * scope is rejecting missing/blank required identifiers, not adding new
 * constraints to a field that was already permissively optional.
 */
public record LookupRefDataRequest(
        @NotBlank String schema,
        @NotBlank String table,
        @NotBlank String codeColumn,
        @NotBlank String descriptionColumn,
        String filterColumn,
        String filterValue) {}
