package io.github.marekpompura.db2mcp.policy.sql;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Empty by default: no schemas are allowed until explicitly configured
 * (ADR 0008, fail-closed). A blank entry (e.g. a stray comma in the YAML
 * list) is rejected at startup (ADR 0015) rather than silently becoming a
 * schema name no real table reference can ever match.
 */
@Validated
@ConfigurationProperties(prefix = "db2mcp")
public record QueryPolicyProperties(List<@NotBlank String> allowedSchemas) {

    public QueryPolicyProperties {
        allowedSchemas = allowedSchemas == null ? List.of() : List.copyOf(allowedSchemas);
    }
}
