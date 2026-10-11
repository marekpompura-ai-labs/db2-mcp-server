package io.github.marekpompura.db2mcp.mcp.tool;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.marekpompura.db2mcp.application.port.out.MetadataPort;
import io.github.marekpompura.db2mcp.domain.error.McpToolException;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

/**
 * #39 acceptance evidence: every {@link SchemaTools} method taking a
 * schema/table/specificName now rejects a blank/null value before ever
 * reaching {@link MetadataPort}. {@code listSchemas} takes no parameters and
 * so has nothing to validate - its own coverage (already exercised by {@code
 * FakeAdapterMcpToolsTest}/{@code McpConfigTest}) is unaffected by this
 * class.
 */
class SchemaToolsTest {

    private final MetadataPort metadataPort = mock(MetadataPort.class);
    private final SchemaTools schemaTools = new SchemaTools(
            metadataPort, new ToolInputValidator(Validation.buildDefaultValidatorFactory().getValidator()));

    @Test
    void listTables_blankSchema_rejectedBeforeReachingThePort() {
        assertThatThrownBy(() -> schemaTools.listTables(" ")).isInstanceOf(McpToolException.class);
        verify(metadataPort, never()).listTables(anyString());
    }

    @Test
    void listTables_validSchema_delegatesToThePort() {
        schemaTools.listTables("ITSM");
        verify(metadataPort).listTables("ITSM");
    }

    @Test
    void describeTable_blankTable_rejectedBeforeReachingThePort() {
        assertThatThrownBy(() -> schemaTools.describeTable("ITSM", null)).isInstanceOf(McpToolException.class);
        verify(metadataPort, never()).describeTable(anyString(), anyString());
    }

    @Test
    void describeTable_validArgs_delegatesToThePort() {
        schemaTools.describeTable("ITSM", "TICKETS");
        verify(metadataPort).describeTable("ITSM", "TICKETS");
    }

    @Test
    void getTableDependencies_blankSchema_rejectedBeforeReachingThePort() {
        assertThatThrownBy(() -> schemaTools.getTableDependencies("", "TICKETS")).isInstanceOf(McpToolException.class);
        verify(metadataPort, never()).getTableDependencies(anyString(), anyString());
    }

    @Test
    void getTableDependencies_validArgs_delegatesToThePort() {
        schemaTools.getTableDependencies("ITSM", "TICKETS");
        verify(metadataPort).getTableDependencies("ITSM", "TICKETS");
    }

    @Test
    void listProcedures_blankSchema_rejectedBeforeReachingThePort() {
        assertThatThrownBy(() -> schemaTools.listProcedures(null)).isInstanceOf(McpToolException.class);
        verify(metadataPort, never()).listProcedures(anyString());
    }

    @Test
    void listProcedures_validSchema_delegatesToThePort() {
        schemaTools.listProcedures("ITSM");
        verify(metadataPort).listProcedures("ITSM");
    }

    @Test
    void describeProcedure_blankSpecificName_rejectedBeforeReachingThePort() {
        assertThatThrownBy(() -> schemaTools.describeProcedure("ITSM", "  ")).isInstanceOf(McpToolException.class);
        verify(metadataPort, never()).describeProcedure(anyString(), anyString());
    }

    @Test
    void describeProcedure_validArgs_delegatesToThePort() {
        schemaTools.describeProcedure("ITSM", "SPECIFIC_1");
        verify(metadataPort).describeProcedure("ITSM", "SPECIFIC_1");
    }

    @Test
    void getProcedureSource_blankSpecificName_rejectedBeforeReachingThePort() {
        assertThatThrownBy(() -> schemaTools.getProcedureSource("ITSM", "")).isInstanceOf(McpToolException.class);
        verify(metadataPort, never()).getProcedureSource(anyString(), anyString());
    }

    @Test
    void getProcedureSource_validArgs_delegatesToThePort() {
        schemaTools.getProcedureSource("ITSM", "SPECIFIC_1");
        verify(metadataPort).getProcedureSource("ITSM", "SPECIFIC_1");
    }
}
