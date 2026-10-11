package io.github.marekpompura.db2mcp.adapter.observability;

/**
 * Thrown by {@link AuditFileAvailabilityValidator} when the configured audit
 * destination cannot be created or opened for writing. Deliberately a
 * distinct type (not a bare {@code IllegalStateException}) so a caller -
 * test or operator tooling - can distinguish "the audit trail is broken"
 * from any other startup failure without parsing a message string.
 */
public class AuditFileUnavailableException extends RuntimeException {

    public AuditFileUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
