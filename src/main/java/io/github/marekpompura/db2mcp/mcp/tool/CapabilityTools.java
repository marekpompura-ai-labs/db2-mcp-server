package io.github.marekpompura.db2mcp.mcp.tool;

import io.github.marekpompura.db2mcp.application.port.out.CapabilityPort;
import io.github.marekpompura.db2mcp.domain.model.CapabilityManifest;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * Capability discovery (database-portability extension section 5.2, ADR
 * 0028) - unlike {@link QueryTools}/{@link ReferenceDataTools}, always
 * registered regardless of capability profile (see {@code McpConfig}), the
 * same way {@link SchemaTools} is: a client under {@code METADATA_ONLY}
 * must be able to discover that row-data tools are effectively disabled,
 * not just find them silently absent with no explanation.
 */
@Component
public class CapabilityTools {

    private final CapabilityPort capabilityPort;

    public CapabilityTools(CapabilityPort capabilityPort) {
        this.capabilityPort = capabilityPort;
    }

    @Tool(
            description = "Report this server's effective capabilities, resource limits, and connected database "
                    + "identity - a versioned manifest, not a static description of the code")
    public CapabilityManifest getCapabilities() {
        return capabilityPort.capabilities();
    }
}
