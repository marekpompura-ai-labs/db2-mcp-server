package io.github.marekpompura.db2mcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.marekpompura.db2mcp.application.port.out.CapabilityPort;
import io.github.marekpompura.db2mcp.application.port.out.ExplainPort;
import io.github.marekpompura.db2mcp.application.port.out.MetadataPort;
import io.github.marekpompura.db2mcp.application.port.out.QueryPort;
import io.github.marekpompura.db2mcp.mcp.tool.CapabilityTools;
import io.github.marekpompura.db2mcp.mcp.tool.ExplainTools;
import io.github.marekpompura.db2mcp.mcp.tool.QueryTools;
import io.github.marekpompura.db2mcp.mcp.tool.ReferenceDataTools;
import io.github.marekpompura.db2mcp.mcp.tool.SchemaTools;
import io.github.marekpompura.db2mcp.mcp.tool.ToolInputValidator;
import jakarta.validation.Validation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

/**
 * Guards the MCP tool contract (brief section 11.3): name, description, and
 * JSON input schema for every tool. A checked-in golden file
 * (mcp-contract-snapshot.json) fails this test the moment any of those
 * changes unintentionally - renaming a tool, tweaking a description,
 * changing a parameter type - forcing the change to be a deliberate,
 * reviewed diff instead of a silent client-breaking surprise.
 *
 * <p>{@link SchemaTools}, {@link QueryTools}, {@link ReferenceDataTools},
 * {@link CapabilityTools}, and {@link ExplainTools} are all instantiated
 * directly with mocked ports, deliberately bypassing the capability-profile
 * gate in {@code McpConfig} (ADR 0006) so the snapshot always covers all 11
 * tools regardless of which profile is active - the contract of a tool
 * that's currently hidden still needs to be pinned, not skipped.
 */
class McpContractSnapshotTest {

    private static final Path GOLDEN_FILE = Path.of("src/test/resources/mcp-contract-snapshot.json");

    @Test
    void toolContractsMatchGoldenSnapshot() throws IOException {
        ToolInputValidator validator =
                new ToolInputValidator(Validation.buildDefaultValidatorFactory().getValidator());
        MethodToolCallbackProvider provider = MethodToolCallbackProvider.builder()
                .toolObjects(
                        new SchemaTools(mock(MetadataPort.class), validator),
                        new QueryTools(mock(QueryPort.class), validator),
                        new ReferenceDataTools(mock(MetadataPort.class), validator),
                        new CapabilityTools(mock(CapabilityPort.class)),
                        new ExplainTools(mock(ExplainPort.class), validator))
                .build();

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

        List<Map<String, Object>> tools = List.of(provider.getToolCallbacks()).stream()
                .map(ToolCallback::getToolDefinition)
                .sorted(Comparator.comparing(ToolDefinition::name))
                .map(def -> toContractMap(mapper, def))
                .collect(Collectors.toList());

        String actual = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(tools) + "\n";

        if (!Files.exists(GOLDEN_FILE)) {
            Files.writeString(GOLDEN_FILE, actual, StandardCharsets.UTF_8);
            throw new AssertionError(
                    "No golden snapshot existed yet - wrote " + GOLDEN_FILE + ". Review it, then rerun the test.");
        }

        String expected = Files.readString(GOLDEN_FILE, StandardCharsets.UTF_8);
        assertEquals(
                expected,
                actual,
                "MCP tool contract changed. If this is intentional, review the diff, delete "
                        + GOLDEN_FILE
                        + ", rerun this test to regenerate it, and commit the new snapshot.");
    }

    private static Map<String, Object> toContractMap(ObjectMapper mapper, ToolDefinition def) {
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("name", def.name());
        contract.put("description", def.description());
        try {
            contract.put("inputSchema", mapper.readValue(def.inputSchema(), new TypeReference<Map<String, Object>>() {}));
        } catch (IOException e) {
            throw new UncheckedIOException("Tool " + def.name() + " has an unparseable input schema", e);
        }
        return contract;
    }
}
