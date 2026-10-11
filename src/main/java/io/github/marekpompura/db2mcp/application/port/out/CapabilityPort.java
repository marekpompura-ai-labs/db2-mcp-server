package io.github.marekpompura.db2mcp.application.port.out;

import io.github.marekpompura.db2mcp.domain.model.CapabilityManifest;

/**
 * Outbound port for capability discovery (database-portability extension
 * section 7.1, ADR 0028). Unlike {@link MetadataPort} and {@link
 * QueryPort}, this port's tool is registered unconditionally under every
 * capability profile (ADR 0006) - a client under {@code METADATA_ONLY}
 * must still be able to discover that {@code readOnlyQuery} is effectively
 * disabled, not just have that tool silently absent with no explanation.
 */
public interface CapabilityPort {

    CapabilityManifest capabilities();
}
