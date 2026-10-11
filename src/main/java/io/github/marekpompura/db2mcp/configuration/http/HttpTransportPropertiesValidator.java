package io.github.marekpompura.db2mcp.configuration.http;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fails startup fast (ADR 0015's pattern, applied to the HTTP transport
 * prototype, thread 6/ADR 0037) if {@code db2mcp.http.enabled=true} but the
 * bearer token needed to actually gate requests is missing, or {@code
 * spring.main.web-application-type} hasn't also been switched to {@code
 * servlet} - the same reasoning {@code AuditFileAvailabilityValidator}
 * already applies to a broken audit destination: a misconfigured,
 * immediately-fixable operator error should stop the server from starting,
 * not silently produce a transport that can never actually listen.
 *
 * <p>The second check exists for a real, distinct footgun this design has:
 * {@code db2mcp.http.enabled} and {@code spring.main.web-application-type}
 * are two separate keys an operator must set in sync. Set only the first,
 * and the app boots as a non-web context - the {@code @ConditionalOnProperty}-gated
 * HTTP beans never register, nothing listens on any port, and nothing about
 * that is visibly wrong without this check.
 *
 * <p>A third check (minimum token length) was added after
 * {@code docs/security/http-transport-security-review-2026-08-29.md}
 * (SR-002) found that only blankness was checked - an operator could set a
 * one-character token and the server would start and accept it, making
 * {@link BearerTokenAuthenticationFilter}'s constant-time comparison
 * meaningless (constant-time only protects a token that has real entropy
 * to begin with).
 */
@Component
public class HttpTransportPropertiesValidator implements ApplicationRunner {

    // Shared with BearerTokenAuthenticationFilter, which enforces the same
    // floor at bean-construction time (HTTP-P1-1, 2026-09-21 review). Kept
    // here as a backstop that produces the fuller operator-facing message,
    // and because this validator also owns the web-application-type check
    // below, which has no filter-side equivalent.
    private static final int MIN_BEARER_TOKEN_LENGTH = BearerTokenAuthenticationFilter.MIN_BEARER_TOKEN_LENGTH;

    private final HttpTransportProperties properties;
    private final Environment environment;

    public HttpTransportPropertiesValidator(HttpTransportProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.enabled()) {
            return;
        }
        if (properties.bearerToken().isBlank()) {
            // Load-bearing line for this whole feature's safety: only the
            // PROPERTY NAME is interpolated into this message, never
            // properties.bearerToken() itself - see
            // HttpTransportPropertiesValidatorTest.run_exceptionMessage_neverContainsTheBearerTokenValue,
            // which exists specifically to catch a future edit that adds
            // the raw token value here.
            throw new HttpTransportConfigurationException("Property: db2mcp.http.enabled / Value: true / "
                    + "Reason: db2mcp.http.bearer-token is blank - HTTP transport cannot start with "
                    + "enabled=true and no bearer token configured; either set DB2MCP_HTTP_BEARER_TOKEN "
                    + "or set db2mcp.http.enabled=false.");
        }
        if (properties.bearerToken().length() < MIN_BEARER_TOKEN_LENGTH) {
            // Only the LENGTH (a non-sensitive integer), never the token
            // value itself, is interpolated here - same discipline as the
            // blank-token message above.
            throw new HttpTransportConfigurationException("Property: db2mcp.http.bearer-token / Reason: token is "
                    + properties.bearerToken().length() + " characters, shorter than the required minimum of "
                    + MIN_BEARER_TOKEN_LENGTH + " - a short token is too easy to guess even with constant-time "
                    + "comparison; generate one with e.g. `openssl rand -hex 32`.");
        }
        String webApplicationType = environment.getProperty("spring.main.web-application-type");
        if (!"servlet".equalsIgnoreCase(webApplicationType)) {
            throw new HttpTransportConfigurationException("Property: db2mcp.http.enabled / Value: true / "
                    + "Reason: spring.main.web-application-type is \"" + webApplicationType + "\", not \"servlet\" - "
                    + "set DB2MCP_HTTP_WEB_APPLICATION_TYPE=servlet alongside DB2MCP_HTTP_ENABLED=true, "
                    + "otherwise no servlet container starts and nothing listens despite enabled=true.");
        }
    }
}
