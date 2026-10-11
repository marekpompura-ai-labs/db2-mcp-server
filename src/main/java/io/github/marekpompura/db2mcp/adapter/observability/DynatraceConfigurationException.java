package io.github.marekpompura.db2mcp.adapter.observability;

/**
 * Thrown by {@link DynatraceExportPropertiesValidator} when {@code
 * db2mcp.dynatrace.enabled=true} but the required environment URL/API
 * token are missing or malformed. Deliberately a distinct type (not a
 * bare {@code IllegalStateException}), matching {@link
 * AuditFileUnavailableException}'s precedent, so a caller can distinguish
 * "Dynatrace export is misconfigured" from any other startup failure
 * without parsing a message string.
 */
public class DynatraceConfigurationException extends RuntimeException {

    public DynatraceConfigurationException(String message) {
        super(message);
    }
}
