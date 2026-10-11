package io.github.marekpompura.db2mcp.policy.concurrency;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Bounds concurrent MCP tool executions (ADR 0035, master brief section
 * 7.5: "bound concurrent tool executions"). Deliberately covers every
 * registered tool, not just {@code executeQuery} - {@link
 * io.github.marekpompura.db2mcp.configuration.AdmissionControlledToolCallback}
 * wraps all of them uniformly at the single {@code ToolCallback} choke
 * point in {@code McpConfig}. The default (10) is set modestly above
 * Hikari's own {@code maximum-pool-size} (5, {@code application.yml})
 * since non-DB2 tools like {@code getCapabilities} also count toward it -
 * illustrative and not load-tested, same honesty framing ADR 0032/0034
 * already used for their own defaults.
 */
@Validated
@ConfigurationProperties(prefix = "db2mcp.concurrency")
public record ConcurrencyLimitsProperties(@DefaultValue("10") @Positive int maxConcurrentToolExecutions) {
}
