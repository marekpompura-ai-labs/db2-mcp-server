package io.github.marekpompura.db2mcp.mcp.tool;

import io.github.marekpompura.db2mcp.application.port.out.MetadataPort;
import io.github.marekpompura.db2mcp.domain.model.BoundedList;
import io.github.marekpompura.db2mcp.domain.model.ColumnInfo;
import io.github.marekpompura.db2mcp.domain.model.ProcedureDetail;
import io.github.marekpompura.db2mcp.domain.model.ProcedureInfo;
import io.github.marekpompura.db2mcp.domain.model.ProcedureSource;
import io.github.marekpompura.db2mcp.domain.model.SchemaInfo;
import io.github.marekpompura.db2mcp.domain.model.TableDependencyInfo;
import io.github.marekpompura.db2mcp.domain.model.TableInfo;
import io.github.marekpompura.db2mcp.mcp.tool.request.DescribeProcedureRequest;
import io.github.marekpompura.db2mcp.mcp.tool.request.DescribeTableRequest;
import io.github.marekpompura.db2mcp.mcp.tool.request.GetProcedureSourceRequest;
import io.github.marekpompura.db2mcp.mcp.tool.request.GetTableDependenciesRequest;
import io.github.marekpompura.db2mcp.mcp.tool.request.ListProceduresRequest;
import io.github.marekpompura.db2mcp.mcp.tool.request.ListTablesRequest;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class SchemaTools {

    private final MetadataPort metadataPort;
    private final ToolInputValidator validator;

    public SchemaTools(MetadataPort metadataPort, ToolInputValidator validator) {
        this.metadataPort = metadataPort;
        this.validator = validator;
    }

    @Tool(description = "List all non-system schemas in the DB2 database")
    public BoundedList<SchemaInfo> listSchemas() {
        return metadataPort.listSchemas();
    }

    @Tool(description = "List all tables and views in a given DB2 schema")
    public BoundedList<TableInfo> listTables(@ToolParam(description = "Schema name") String schema) {
        validator.validate(new ListTablesRequest(schema));
        return metadataPort.listTables(schema);
    }

    @Tool(description = "Describe columns of a table: name, type, length, nullability, primary key flag")
    public BoundedList<ColumnInfo> describeTable(
            @ToolParam(description = "Schema name") String schema,
            @ToolParam(description = "Table name") String table) {
        validator.validate(new DescribeTableRequest(schema, table));
        return metadataPort.describeTable(schema, table);
    }

    @Tool(description = "Foreign key relationships in and out of a table")
    public BoundedList<TableDependencyInfo> getTableDependencies(
            @ToolParam(description = "Schema name") String schema,
            @ToolParam(description = "Table name") String table) {
        validator.validate(new GetTableDependenciesRequest(schema, table));
        return metadataPort.getTableDependencies(schema, table);
    }

    @Tool(description = "List all stored procedures in a schema, including each overload's specificName")
    public BoundedList<ProcedureInfo> listProcedures(@ToolParam(description = "Schema name") String schema) {
        validator.validate(new ListProceduresRequest(schema));
        return metadataPort.listProcedures(schema);
    }

    @Tool(
            description = "Parameters, types, and directions (IN/OUT/INOUT) of a stored procedure. "
                    + "Procedures can be overloaded - pass specificName from listProcedures, not procedureName, "
                    + "to select one unambiguously")
    public ProcedureDetail describeProcedure(
            @ToolParam(description = "Schema name") String schema,
            @ToolParam(description = "specificName from listProcedures, not procedureName") String specificName) {
        validator.validate(new DescribeProcedureRequest(schema, specificName));
        return metadataPort.describeProcedure(schema, specificName);
    }

    @Tool(
            description = "Source DDL of a stored procedure from SYSCAT.PROCEDURES.TEXT - SQL procedures only. "
                    + "Procedures can be overloaded - pass specificName from listProcedures, not procedureName, "
                    + "to select one unambiguously")
    public ProcedureSource getProcedureSource(
            @ToolParam(description = "Schema name") String schema,
            @ToolParam(description = "specificName from listProcedures, not procedureName") String specificName) {
        validator.validate(new GetProcedureSourceRequest(schema, specificName));
        return metadataPort.getProcedureSource(schema, specificName);
    }
}
