package io.github.marekpompura.db2mcp.configuration;

import io.github.marekpompura.db2mcp.adapter.observability.AuditLogger;
import io.github.marekpompura.db2mcp.adapter.observability.MetricsRecorder;
import io.github.marekpompura.db2mcp.adapter.observability.SpanRecorder;
import io.github.marekpompura.db2mcp.mcp.tool.CapabilityTools;
import io.github.marekpompura.db2mcp.mcp.tool.ExplainTools;
import io.github.marekpompura.db2mcp.mcp.tool.QueryTools;
import io.github.marekpompura.db2mcp.mcp.tool.ReferenceDataTools;
import io.github.marekpompura.db2mcp.mcp.tool.SchemaTools;
import io.github.marekpompura.db2mcp.policy.capability.CapabilityProfile;
import io.github.marekpompura.db2mcp.policy.capability.CapabilityProperties;
import io.github.marekpompura.db2mcp.policy.concurrency.ToolExecutionAdmissionGate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpConfig {

    @Bean
    public ToolCallbackProvider schemaToolCallbackProvider(
            SchemaTools schemaTools,
            QueryTools queryTools,
            ReferenceDataTools referenceDataTools,
            CapabilityTools capabilityTools,
            ExplainTools explainTools,
            CapabilityProperties capabilityProperties,
            ToolExecutionAdmissionGate admissionGate,
            AuditLogger auditLogger,
            MetricsRecorder metricsRecorder,
            SpanRecorder spanRecorder) {
        List<Object> tools = new ArrayList<>();
        tools.add(schemaTools);
        // getCapabilities is always visible, under every profile (ADR 0028) - a
        // client under METADATA_ONLY must be able to discover that readOnlyQuery
        // is effectively disabled, not just find executeQuery silently absent.
        tools.add(capabilityTools);
        CapabilityProfile capabilityProfile = capabilityProperties.capabilityProfile();
        // executeQuery and lookupRefData both return row data, not metadata, so
        // both require SAFE_QUERY/ADVANCED_QUERY (ADR 0006, CR-001 2026-08-14
        // code review); under METADATA_ONLY neither is even visible to the MCP
        // client, not just rejected when called.
        if (capabilityProfile.allowsQueryExecution()) {
            tools.add(queryTools);
            tools.add(referenceDataTools);
        }
        // explainQuery is narrower still than the row-data gate above - it's
        // ADVANCED_QUERY-only (ADR 0033, master brief section 6.3), not
        // granted by SAFE_QUERY, so it needs its own condition rather than
        // folding into the block above.
        if (capabilityProfile == CapabilityProfile.ADVANCED_QUERY) {
            tools.add(explainTools);
        }
        ToolCallback[] callbacks =
                MethodToolCallbackProvider.builder().toolObjects(tools.toArray()).build().getToolCallbacks();
        // Every registered tool, under every profile, wrapped uniformly with
        // the concurrent-tool-execution admission gate (ADR 0035, master
        // brief section 7.5) - a single shared choke point here rather than
        // duplicating a semaphore-acquire call into every adapter method.
        ToolCallback[] admissionControlled = Arrays.stream(callbacks)
                .map(callback -> new AdmissionControlledToolCallback(
                        callback, admissionGate, capabilityProfile, auditLogger, metricsRecorder, spanRecorder))
                .toArray(ToolCallback[]::new);
        return ToolCallbackProvider.from(admissionControlled);
    }
}
