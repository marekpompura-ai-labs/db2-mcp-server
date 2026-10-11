package io.github.marekpompura.db2mcp.application.port.out;

import io.github.marekpompura.db2mcp.domain.model.BoundedList;
import io.github.marekpompura.db2mcp.domain.model.ColumnInfo;
import io.github.marekpompura.db2mcp.domain.model.ProcedureDetail;
import io.github.marekpompura.db2mcp.domain.model.ProcedureInfo;
import io.github.marekpompura.db2mcp.domain.model.ProcedureSource;
import io.github.marekpompura.db2mcp.domain.model.ReferenceDataEntry;
import io.github.marekpompura.db2mcp.domain.model.SchemaInfo;
import io.github.marekpompura.db2mcp.domain.model.TableDependencyInfo;
import io.github.marekpompura.db2mcp.domain.model.TableInfo;

/**
 * Outbound port for schema/procedure metadata and reference-data lookups
 * (database-portability extension section 7.1, ADR 0027). The mcp.tool
 * layer depends only on this interface, never on adapter.db2 or JDBC
 * directly (ADR 0005); {@code adapter.db2.Db2MetadataAdapter} is the only
 * implementation today, but any future non-Db2 adapter would implement this
 * same contract, expressed entirely in domain-owned types.
 */
public interface MetadataPort {

    // Every list-producing method returns BoundedList, not a bare List
    // (#62, 2026-09-09 ChatGPT re-review, RR-P0-1): the envelope is what
    // carries the truncated flag, without which maxPayloadBytes could cut a
    // result the caller then read as complete.
    BoundedList<SchemaInfo> listSchemas();

    BoundedList<TableInfo> listTables(String schema);

    BoundedList<ColumnInfo> describeTable(String schema, String table);

    BoundedList<TableDependencyInfo> getTableDependencies(String schema, String table);

    BoundedList<ProcedureInfo> listProcedures(String schema);

    // specificName, not procedureName: DB2 allows overloaded procedures
    // sharing a schema and name (CR-003, ADR 0022) - use the specificName
    // from listProcedures' result to select one unambiguously.
    ProcedureDetail describeProcedure(String schema, String specificName);

    ProcedureSource getProcedureSource(String schema, String specificName);

    BoundedList<ReferenceDataEntry> lookupRefData(
            String schema, String table, String codeColumn, String descriptionColumn,
            String filterColumn, String filterValue);
}
