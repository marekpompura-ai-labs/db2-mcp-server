package io.github.marekpompura.db2mcp.mcp.tool;

import io.github.marekpompura.db2mcp.application.port.out.MetadataPort;
import io.github.marekpompura.db2mcp.domain.model.BoundedList;
import io.github.marekpompura.db2mcp.domain.model.ReferenceDataEntry;
import io.github.marekpompura.db2mcp.mcp.tool.request.LookupRefDataRequest;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * lookupRefData returns row data, not metadata (CR-001, 2026-08-14 code
 * review) - it belongs in its own conditionally-registered tool class, the
 * same way {@link QueryTools} is kept separate from {@link SchemaTools}, so
 * {@code McpConfig} can omit it from tool discovery under {@code
 * METADATA_ONLY} instead of merely rejecting it when called.
 */
@Component
public class ReferenceDataTools {

    private final MetadataPort metadataPort;
    private final ToolInputValidator validator;

    public ReferenceDataTools(MetadataPort metadataPort, ToolInputValidator validator) {
        this.metadataPort = metadataPort;
        this.validator = validator;
    }

    @Tool(description = "Reference data lookup - CODE and DESCRIPTION from any table, with an optional category filter")
    public BoundedList<ReferenceDataEntry> lookupRefData(
            @ToolParam(description = "Schema name") String schema,
            @ToolParam(description = "Table name") String table,
            @ToolParam(description = "Column holding the code value") String codeColumn,
            @ToolParam(description = "Column holding the description value") String descriptionColumn,
            @ToolParam(description = "Optional discriminator column, e.g. a category column", required = false)
                    String filterColumn,
            @ToolParam(description = "Value to filter filterColumn by, if filterColumn is set", required = false)
                    String filterValue) {
        validator.validate(new LookupRefDataRequest(schema, table, codeColumn, descriptionColumn, filterColumn, filterValue));
        return metadataPort.lookupRefData(schema, table, codeColumn, descriptionColumn, filterColumn, filterValue);
    }
}
