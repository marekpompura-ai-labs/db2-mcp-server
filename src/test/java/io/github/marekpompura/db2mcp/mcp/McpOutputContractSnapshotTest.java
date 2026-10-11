package io.github.marekpompura.db2mcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.marekpompura.db2mcp.domain.model.BoundedList;
import io.github.marekpompura.db2mcp.domain.model.CapabilityManifest;
import io.github.marekpompura.db2mcp.domain.model.ColumnInfo;
import io.github.marekpompura.db2mcp.domain.model.DatabaseDescriptor;
import io.github.marekpompura.db2mcp.domain.model.DatabaseProduct;
import io.github.marekpompura.db2mcp.domain.model.EffectiveCapabilities;
import io.github.marekpompura.db2mcp.domain.model.EffectiveLimits;
import io.github.marekpompura.db2mcp.domain.model.ExplainOperator;
import io.github.marekpompura.db2mcp.domain.model.ExplainPlanResult;
import io.github.marekpompura.db2mcp.domain.model.IdentifierRules;
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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Guards the MCP tool contract's *output* shape (CR-008, 2026-08-14 code
 * review, ADR 0025) - the golden snapshot in {@link McpContractSnapshotTest}
 * (ADR 0013) only pins tool name, description, and input schema. A change
 * to {@link ProcedureInfo}'s fields (exactly what ADR 0022 did, adding
 * {@code specificName}) would not have been caught by that test at all;
 * this one exists specifically to force that kind of change through a
 * reviewed diff instead of landing silently.
 *
 * <p>One representative instance of each of the eleven tools' actual return
 * type, keyed by tool name, serialized the same way
 * {@link McpContractSnapshotTest} serializes input schemas (sorted keys,
 * pretty-printed) so a diff is easy to read.
 */
class McpOutputContractSnapshotTest {

    private static final Path GOLDEN_FILE = Path.of("src/test/resources/mcp-output-contract-snapshot.json");

    @Test
    void toolOutputShapesMatchGoldenSnapshot() throws IOException {
        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("listSchemas", BoundedList.of(List.of(new SchemaInfo("ITSM")), false, "33333333-3333-3333-3333-333333333333"));
        outputs.put("listTables", BoundedList.of(List.of(new TableInfo("ITSM", "TICKET", "TABLE")), false, "33333333-3333-3333-3333-333333333333"));
        outputs.put(
                "describeTable",
                BoundedList.of(List.of(new ColumnInfo("TICKET_ID", "INTEGER", 4, 0, false, true, 0)), false, "33333333-3333-3333-3333-333333333333"));
        outputs.put(
                "getTableDependencies",
                BoundedList.of(
                        List.of(new TableDependencyInfo(
                                "FK_TICKET_CUSTOMER", "OUTBOUND", "ITSM", "TICKET", "CUSTOMER_ID", "ITSM", "CUSTOMER", "CUSTOMER_ID")),
                        false,
                        "33333333-3333-3333-3333-333333333333"));
        outputs.put(
                "listProcedures",
                BoundedList.of(
                        List.of(new ProcedureInfo("ITSM", "STP_FIND_TICKETS", "SQL240101120000000", "SQL", 2)), false, "33333333-3333-3333-3333-333333333333"));
        outputs.put(
                "describeProcedure",
                new ProcedureDetail(
                        new ProcedureInfo("ITSM", "STP_FIND_TICKETS", "SQL240101120000000", "SQL", 2),
                        List.of(new ProcedureParameterInfo("P_STATUS", "CHARACTER", "IN", 1))));
        outputs.put("getProcedureSource", new ProcedureSource("ITSM", "STP_FIND_TICKETS", "SQL240101120000000", "CREATE PROCEDURE ..."));
        outputs.put("lookupRefData", BoundedList.of(List.of(new ReferenceDataEntry("H", "High")), false, "33333333-3333-3333-3333-333333333333"));
        outputs.put(
                "getCapabilities",
                new CapabilityManifest(
                        "1.0",
                        new DatabaseDescriptor(DatabaseProduct.DB2, "11.5.7", "DB2_LUW", new IdentifierRules('"', true)),
                        new EffectiveCapabilities(true, true, true, true, true, true, true, true, false),
                        new EffectiveLimits(1000, 16384, 1048576, 5, 3),
                        "44444444-4444-4444-4444-444444444444"));
        outputs.put(
                "executeQuery",
                new QueryResult(
                        List.of(new QueryColumn("TICKET_ID", "INTEGER"), new QueryColumn("TITLE", "VARCHAR")),
                        List.of(List.of(101, "Cannot connect to VPN")),
                        1,
                        false,
                        "11111111-1111-1111-1111-111111111111"));
        outputs.put(
                "explainQuery",
                new ExplainPlanResult(
                        "SELECT * FROM ITSM.TICKET",
                        List.of(new ExplainOperator(1, "TBSCAN", 12.5), new ExplainOperator(2, "RETURN", 12.5)),
                        false,
                        "22222222-2222-2222-2222-222222222222"));

        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        mapper.configure(SerializationFeature.INDENT_OUTPUT, true);
        // DefaultPrettyPrinter's default *object* indenter uses
        // System.lineSeparator() for its line breaks - "\r\n" on Windows,
        // "\n" on Linux/CI (its array indenter is unaffected: arrays use
        // FixedSpaceIndenter, which is single-space and never platform-
        // dependent, so only the object indenter needs overriding here).
        // The golden file below is checked in with "\n" (and .gitattributes
        // keeps it that way on checkout), so without this, the snapshot
        // comparison spuriously fails on every Windows checkout even when
        // the actual contract hasn't changed at all.
        mapper.setDefaultPrettyPrinter(new DefaultPrettyPrinter().withObjectIndenter(new DefaultIndenter("  ", "\n")));

        String actual = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(outputs) + "\n";

        if (!Files.exists(GOLDEN_FILE)) {
            Files.writeString(GOLDEN_FILE, actual, StandardCharsets.UTF_8);
            throw new AssertionError(
                    "No golden output snapshot existed yet - wrote " + GOLDEN_FILE + ". Review it, then rerun the test.");
        }

        String expected = Files.readString(GOLDEN_FILE, StandardCharsets.UTF_8);
        assertEquals(
                expected,
                actual,
                "MCP tool output shape changed. If this is intentional, review the diff, delete "
                        + GOLDEN_FILE
                        + ", rerun this test to regenerate it, and commit the new snapshot.");
    }
}
