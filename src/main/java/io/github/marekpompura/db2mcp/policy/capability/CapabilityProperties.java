package io.github.marekpompura.db2mcp.policy.capability;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "db2mcp")
public record CapabilityProperties(@DefaultValue("METADATA_ONLY") @NotNull CapabilityProfile capabilityProfile) {
}
