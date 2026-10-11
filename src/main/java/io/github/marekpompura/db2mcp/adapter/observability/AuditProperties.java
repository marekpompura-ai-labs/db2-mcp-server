package io.github.marekpompura.db2mcp.adapter.observability;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Where the audit trail (ADR 0010) is written. Externalized (CR-011, 2026-08-14
 * code review, ADR 0026) so an operator can point it at a mounted volume
 * instead of the working-directory-relative default, and read by both
 * {@code logback-spring.xml} (via {@code <springProperty>}, for the actual
 * rolling file appender) and {@link AuditFileAvailabilityValidator} (which
 * checks the same resolved path is writable before the server starts
 * accepting tool calls).
 */
@Validated
@ConfigurationProperties(prefix = "db2mcp.audit")
public record AuditProperties(@DefaultValue("logs/audit.log") @NotBlank String file) {
}
