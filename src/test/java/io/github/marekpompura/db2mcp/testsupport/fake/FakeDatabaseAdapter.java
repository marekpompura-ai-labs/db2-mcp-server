package io.github.marekpompura.db2mcp.testsupport.fake;

import io.github.marekpompura.db2mcp.application.port.out.MetadataPort;
import io.github.marekpompura.db2mcp.application.port.out.QueryPort;
import io.github.marekpompura.db2mcp.domain.model.BoundedList;
import io.github.marekpompura.db2mcp.domain.model.ColumnInfo;
import io.github.marekpompura.db2mcp.domain.model.ProcedureDetail;
import io.github.marekpompura.db2mcp.domain.model.ProcedureInfo;
import io.github.marekpompura.db2mcp.domain.model.ProcedureParameterInfo;
import io.github.marekpompura.db2mcp.domain.model.ProcedureSource;
import io.github.marekpompura.db2mcp.domain.model.QueryColumn;
import io.github.marekpompura.db2mcp.domain.model.QueryResult;
import io.github.marekpompura.db2mcp.domain.model.ReferenceDataEntry;
import io.github.marekpompura.db2mcp.domain.model.SchemaInfo;
import io.github.marekpompura.db2mcp.domain.model.TableDependencyInfo;
import io.github.marekpompura.db2mcp.domain.model.TableInfo;
import java.util.List;
import java.util.UUID;

/**
 * Portability evidence, not a second product (database-portability
 * extension section 15, "use a fake adapter only to prove dependency
 * inversion and contract portability" - ADR 0028). Test-only (lives under
 * {@code src/test}, never referenced from {@code src/main} or wired into
 * the real Spring context) - proves {@code MetadataPort}/{@code QueryPort}
 * and everything above them (application, {@code mcp.tool}) can be
 * satisfied by an implementation with zero {@code java.sql}, {@code
 * javax.sql}, or DB2-specific imports, not merely diagrammed as capable of
 * that.
 *
 * <p>Unconditionally successful: no capability/schema gating, no
 * not-found branch, no SQL parsing of {@link #executeQuery}'s argument -
 * this fake exists to prove the MCP/application layers work with *any*
 * adapter shape, not to reproduce Db2's specific validation or error
 * semantics (that's already covered, against Db2 itself, by {@code
 * Db2MetadataAdapter}/{@code Db2QueryAdapter}'s own tests).
 *
 * <p>Deliberately does not implement {@code CapabilityPort}: {@link
 * io.github.marekpompura.db2mcp.domain.model.DatabaseProduct} is a closed,
 * evidence-gated enum ("future values must not be interpreted as
 * implemented support without an adapter and integration evidence",
 * extension section 5.1) - reporting {@code DatabaseProduct.DB2} from a
 * non-Db2 test double would misrepresent it, and adding a {@code FAKE}
 * enum constant would let it bind as a real value of {@code
 * db2mcp.database.product} in production, weakening the fail-fast
 * adapter-selection guarantee ADR 0028 built. Not implementing the port at
 * all avoids both problems - documented as a deliberate scope boundary in
 * ADR 0029, not an oversight.
 */
public final class FakeDatabaseAdapter implements MetadataPort, QueryPort {

    public static final String SCHEMA = "FAKESCHEMA";
    public static final String TABLE = "WIDGET";
    public static final String REF_TABLE = "WIDGET_STATUS_REF";
    public static final String PROCEDURE_NAME = "FIND_WIDGETS";
    public static final String SPECIFIC_NAME = "FAKE_SPECIFIC_1";

    /**
     * The fake never truncates - it exists to exercise the contract's
     * shape, not its limits (#62 turned every list-producing port method
     * into a {@link BoundedList}).
     */
    private static <T> BoundedList<T> bounded(List<T> items) {
        return BoundedList.of(items, false, UUID.randomUUID().toString());
    }

    @Override
    public BoundedList<SchemaInfo> listSchemas() {
        return bounded(List.of(new SchemaInfo(SCHEMA)));
    }

    @Override
    public BoundedList<TableInfo> listTables(String schema) {
        return bounded(List.of(new TableInfo(schema, TABLE, "TABLE"), new TableInfo(schema, REF_TABLE, "TABLE")));
    }

    @Override
    public BoundedList<ColumnInfo> describeTable(String schema, String table) {
        return bounded(List.of(
                new ColumnInfo("WIDGET_ID", "INTEGER", 4, 0, false, true, 0),
                new ColumnInfo("NAME", "VARCHAR", 128, 0, false, false, 1),
                new ColumnInfo("CATEGORY_ID", "INTEGER", 4, 0, true, false, 2)));
    }

    @Override
    public BoundedList<TableDependencyInfo> getTableDependencies(String schema, String table) {
        return bounded(List.of(new TableDependencyInfo(
                "FK_WIDGET_CATEGORY", "OUTBOUND", schema, table, "CATEGORY_ID", schema, "WIDGET_CATEGORY", "CATEGORY_ID")));
    }

    @Override
    public BoundedList<ProcedureInfo> listProcedures(String schema) {
        return bounded(List.of(new ProcedureInfo(schema, PROCEDURE_NAME, SPECIFIC_NAME, "SQL", 1)));
    }

    @Override
    public ProcedureDetail describeProcedure(String schema, String specificName) {
        return new ProcedureDetail(
                new ProcedureInfo(schema, PROCEDURE_NAME, specificName, "SQL", 1),
                List.of(new ProcedureParameterInfo("P_STATUS", "CHARACTER", "IN", 1)));
    }

    @Override
    public ProcedureSource getProcedureSource(String schema, String specificName) {
        return new ProcedureSource(schema, PROCEDURE_NAME, specificName, "CREATE PROCEDURE " + PROCEDURE_NAME + " ... (fake source)");
    }

    @Override
    public BoundedList<ReferenceDataEntry> lookupRefData(
            String schema, String table, String codeColumn, String descriptionColumn, String filterColumn, String filterValue) {
        return bounded(List.of(new ReferenceDataEntry("A", "Active"), new ReferenceDataEntry("R", "Retired")));
    }

    @Override
    public QueryResult executeQuery(String sql) {
        List<QueryColumn> columns = List.of(new QueryColumn("WIDGET_ID", "INTEGER"), new QueryColumn("NAME", "VARCHAR"));
        List<List<Object>> rows = List.of(List.of(1, "Fake widget one"), List.of(2, "Fake widget two"));
        return new QueryResult(columns, rows, rows.size(), false, UUID.randomUUID().toString());
    }
}
