package io.github.marekpompura.db2mcp.configuration.http;

/**
 * Thrown by {@link HttpTransportPropertiesValidator} when {@code
 * db2mcp.http.enabled=true} but the required bearer token is missing, or
 * {@code spring.main.web-application-type} hasn't also been switched to
 * {@code servlet}. Deliberately a distinct type (not a bare {@code
 * IllegalStateException}), matching {@code DynatraceConfigurationException}'s
 * precedent (ADR 0036), so a caller can distinguish "HTTP transport is
 * misconfigured" from any other startup failure without parsing a message
 * string.
 */
public class HttpTransportConfigurationException extends RuntimeException {

    public HttpTransportConfigurationException(String message) {
        super(message);
    }
}
