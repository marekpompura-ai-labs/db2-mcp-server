package io.github.marekpompura.db2mcp.testsupport.fake;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.marekpompura.db2mcp.domain.model.ColumnInfo;
import io.github.marekpompura.db2mcp.domain.model.ProcedureDetail;
import io.github.marekpompura.db2mcp.domain.model.ProcedureInfo;
import io.github.marekpompura.db2mcp.domain.model.ProcedureSource;
import io.github.marekpompura.db2mcp.domain.model.QueryResult;
import io.github.marekpompura.db2mcp.domain.model.ReferenceDataEntry;
import io.github.marekpompura.db2mcp.domain.model.SchemaInfo;
import io.github.marekpompura.db2mcp.domain.model.TableDependencyInfo;
import io.github.marekpompura.db2mcp.domain.model.TableInfo;
import io.github.marekpompura.db2mcp.mcp.tool.QueryTools;
import io.github.marekpompura.db2mcp.mcp.tool.ReferenceDataTools;
import io.github.marekpompura.db2mcp.mcp.tool.SchemaTools;
import io.github.marekpompura.db2mcp.mcp.tool.ToolInputValidator;
import jakarta.validation.Validation;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The centerpiece of Phase P5's portability evidence (database-portability
 * extension section 15, "Definition of done" section 16 - "fake-adapter
 * contract tests prove that the MCP/application layers are not Db2-bound",
 * ADR 0029). Not a mock-based unit test - the real, production {@link
 * SchemaTools}, {@link QueryTools}, and {@link ReferenceDataTools} classes,
 * wired with {@link FakeDatabaseAdapter}, each of their nine tool methods
 * called for real and asserted on the real returned domain model. If any
 * of {@code mcp.tool} or {@code application.port.out} secretly depended on
 * a Db2/JDBC-specific behavior, this test would fail to compile or fail at
 * runtime - it does neither, with zero {@code java.sql}/{@code
 * com.ibm.db2} anywhere in the call graph, confirmed by inspection of
 * {@link FakeDatabaseAdapter}'s own imports.
 *
 * <p>{@code getCapabilities} is not exercised here - {@link
 * FakeDatabaseAdapter} deliberately does not implement {@code
 * CapabilityPort} (see its class Javadoc for why).
 */
class FakeAdapterMcpToolsTest {

    private final FakeDatabaseAdapter fake = new FakeDatabaseAdapter();
    private final ToolInputValidator validator =
            new ToolInputValidator(Validation.buildDefaultValidatorFactory().getValidator());
    private final SchemaTools schemaTools = new SchemaTools(fake, validator);
    private final QueryTools queryTools = new QueryTools(fake, validator);
    private final ReferenceDataTools referenceDataTools = new ReferenceDataTools(fake, validator);

    @Test
    void listSchemas_returnsTheFakeSchema() {
        List<SchemaInfo> schemas = schemaTools.listSchemas().items();

        assertThat(schemas).containsExactly(new SchemaInfo(FakeDatabaseAdapter.SCHEMA));
    }

    @Test
    void listTables_returnsTheFakeTables() {
        List<TableInfo> tables = schemaTools.listTables(FakeDatabaseAdapter.SCHEMA).items();

        assertThat(tables).extracting(TableInfo::tableName).contains(FakeDatabaseAdapter.TABLE);
    }

    @Test
    void describeTable_returnsColumnsIncludingAPrimaryKey() {
        List<ColumnInfo> columns = schemaTools.describeTable(FakeDatabaseAdapter.SCHEMA, FakeDatabaseAdapter.TABLE).items();

        assertThat(columns).anySatisfy(c -> assertThat(c.primaryKey()).isTrue());
    }

    @Test
    void getTableDependencies_returnsAForeignKeyRelationship() {
        List<TableDependencyInfo> dependencies =
                schemaTools.getTableDependencies(FakeDatabaseAdapter.SCHEMA, FakeDatabaseAdapter.TABLE).items();

        assertThat(dependencies).isNotEmpty();
        assertThat(dependencies.get(0).direction()).isEqualTo("OUTBOUND");
    }

    @Test
    void listProcedures_returnsTheFakeProcedureWithASpecificName() {
        List<ProcedureInfo> procedures = schemaTools.listProcedures(FakeDatabaseAdapter.SCHEMA).items();

        assertThat(procedures).extracting(ProcedureInfo::specificName).containsExactly(FakeDatabaseAdapter.SPECIFIC_NAME);
    }

    @Test
    void describeProcedure_returnsParametersForTheRequestedSpecificName() {
        ProcedureDetail detail = schemaTools.describeProcedure(FakeDatabaseAdapter.SCHEMA, FakeDatabaseAdapter.SPECIFIC_NAME);

        assertThat(detail.procedure().specificName()).isEqualTo(FakeDatabaseAdapter.SPECIFIC_NAME);
        assertThat(detail.parameters()).isNotEmpty();
    }

    @Test
    void getProcedureSource_returnsNonBlankSourceText() {
        ProcedureSource source = schemaTools.getProcedureSource(FakeDatabaseAdapter.SCHEMA, FakeDatabaseAdapter.SPECIFIC_NAME);

        assertThat(source.sourceText()).isNotBlank();
    }

    @Test
    void lookupRefData_returnsCodeDescriptionPairs() {
        List<ReferenceDataEntry> entries = referenceDataTools.lookupRefData(
                FakeDatabaseAdapter.SCHEMA, FakeDatabaseAdapter.REF_TABLE, "CODE", "DESCRIPTION", null, null).items();

        assertThat(entries).containsExactly(new ReferenceDataEntry("A", "Active"), new ReferenceDataEntry("R", "Retired"));
    }

    @Test
    void executeQuery_returnsAStructurallyConsistentResultRegardlessOfSqlText() {
        QueryResult result = queryTools.executeQuery("SELECT * FROM ANYTHING");

        assertThat(result.rowCount()).isEqualTo(result.rows().size());
        assertThat(result.columns()).hasSize(2);
        assertThat(result.truncated()).isFalse();
    }
}
