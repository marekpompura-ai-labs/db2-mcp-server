package io.github.marekpompura.db2mcp.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.marekpompura.db2mcp.adapter.observability.AuditLogger;
import io.github.marekpompura.db2mcp.adapter.observability.MetricsRecorder;
import io.github.marekpompura.db2mcp.adapter.observability.SpanRecorder;
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
import io.github.marekpompura.db2mcp.policy.capability.CapabilityProfile;
import io.github.marekpompura.db2mcp.policy.capability.CapabilityProperties;
import io.github.marekpompura.db2mcp.policy.concurrency.ConcurrencyLimitsProperties;
import io.github.marekpompura.db2mcp.policy.concurrency.ToolExecutionAdmissionGate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.test.simple.SimpleTracer;
import jakarta.validation.Validation;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

/**
 * CR-001 acceptance evidence (2026-08-14 code review): {@code
 * lookupRefData} must be genuinely absent from MCP tool discovery under
 * {@code METADATA_ONLY}, not merely rejected when called - ADR 0006's
 * "invisible, not just rejected" contract, the same one {@code
 * executeQuery} already relied on before this fix.
 */
class McpConfigTest {

    private static ToolCallbackProvider buildProvider(CapabilityProfile profile) {
        McpConfig config = new McpConfig();
        ToolInputValidator validator =
                new ToolInputValidator(Validation.buildDefaultValidatorFactory().getValidator());
        return config.schemaToolCallbackProvider(
                new SchemaTools(mock(MetadataPort.class), validator),
                new QueryTools(mock(QueryPort.class), validator),
                new ReferenceDataTools(mock(MetadataPort.class), validator),
                new CapabilityTools(mock(CapabilityPort.class)),
                new ExplainTools(mock(ExplainPort.class), validator),
                new CapabilityProperties(profile),
                // Real, lightweight instances rather than mocks - matches this
                // suite's existing convention (Db2QueryAdapterIntegrationTest et
                // al.) of preferring the real collaborator when it's cheap to
                // construct.
                new ToolExecutionAdmissionGate(new ConcurrencyLimitsProperties(10)),
                new AuditLogger(),
                new MetricsRecorder(new SimpleMeterRegistry()),
                new SpanRecorder(new SimpleTracer()));
    }

    private static List<String> registeredToolNames(CapabilityProfile profile) {
        return Arrays.stream(buildProvider(profile).getToolCallbacks())
                .map(ToolCallback::getToolDefinition)
                .map(def -> def.name())
                .toList();
    }

    @Test
    void metadataOnly_hidesLookupRefDataAndExecuteQuery() {
        List<String> tools = registeredToolNames(CapabilityProfile.METADATA_ONLY);

        assertThat(tools).doesNotContain("lookupRefData", "executeQuery", "explainQuery");
        assertThat(tools).contains("getCapabilities");
        assertThat(tools).hasSize(8);
    }

    @Test
    void safeQuery_exposesLookupRefDataAndExecuteQueryButNotExplainQuery() {
        List<String> tools = registeredToolNames(CapabilityProfile.SAFE_QUERY);

        assertThat(tools).contains("lookupRefData", "executeQuery", "getCapabilities");
        assertThat(tools).doesNotContain("explainQuery");
        assertThat(tools).hasSize(10);
    }

    // ADR 0033: explainQuery is the one tool ADVANCED_QUERY exposes that
    // SAFE_QUERY doesn't - the first real, tool-registration-visible
    // difference between the two profiles.
    @Test
    void advancedQuery_alsoExposesExplainQuery() {
        List<String> tools = registeredToolNames(CapabilityProfile.ADVANCED_QUERY);

        assertThat(tools).contains("lookupRefData", "executeQuery", "explainQuery", "getCapabilities");
        assertThat(tools).hasSize(11);
    }

    // ADR 0035: every tool must pass through the concurrent-tool-execution
    // admission gate, not just executeQuery - a wrapper silently missing
    // from even one tool would leave that tool's calls unbounded.
    @Test
    void everyRegisteredToolIsWrappedWithTheAdmissionGate() {
        ToolCallbackProvider provider = buildProvider(CapabilityProfile.ADVANCED_QUERY);

        assertThat(provider.getToolCallbacks())
                .isNotEmpty()
                .allSatisfy(callback -> assertThat(callback).isInstanceOf(AdmissionControlledToolCallback.class));
    }
}
