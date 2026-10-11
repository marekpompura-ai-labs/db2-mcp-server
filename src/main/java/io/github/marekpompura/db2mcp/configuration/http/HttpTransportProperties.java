package io.github.marekpompura.db2mcp.configuration.http;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Opt-in HTTP transport prototype config (thread 6, ADR 0037) - {@code
 * enabled} defaults to {@code false}, matching how {@code false} means zero
 * HTTP-related beans are registered at all and stdio (ADR 0001) remains the
 * only active transport (see {@link HttpTransportConfig}).
 *
 * <p>Deliberately no {@code port} field: {@code server.port} is already
 * Spring Boot's own standard servlet-container property, read automatically
 * once {@code spring.main.web-application-type} is {@code servlet}. A
 * duplicate {@code db2mcp.http.port} would just be a second knob that can
 * drift from the real one for no benefit.
 *
 * <p>{@code bearerToken} is a single shared secret checked on every HTTP
 * request by {@link BearerTokenAuthenticationFilter} - not per-client
 * identity, not OAuth 2.1. It's read only by {@link
 * HttpTransportPropertiesValidator} (to check it's non-blank - never
 * logged) and by {@link HttpTransportConfig}'s filter-registration bean
 * factory method - never by {@link
 * io.github.marekpompura.db2mcp.configuration.EffectiveConfigReporter},
 * which is never even constructed with this record as a dependency. That
 * exclusion-by-construction is deliberate, the same pattern already applied
 * there to the DB2 credentials and the Dynatrace API token (ADR 0036).
 */
@Validated
@ConfigurationProperties(prefix = "db2mcp.http")
public record HttpTransportProperties(@DefaultValue("false") boolean enabled, @DefaultValue("") String bearerToken) {
}
