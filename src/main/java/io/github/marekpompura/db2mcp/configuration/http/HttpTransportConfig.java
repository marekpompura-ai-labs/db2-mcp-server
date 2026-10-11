package io.github.marekpompura.db2mcp.configuration.http;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import tools.jackson.databind.json.JsonMapper;

/**
 * Hand-wires the HTTP transport prototype (thread 6, ADR 0037), the same
 * "hand-wire and understand every autoconfiguration you pull in" convention
 * {@link io.github.marekpompura.db2mcp.configuration.StdioLifecycleConfig}
 * already established for stdio - not {@code
 * spring-ai-starter-mcp-server-webmvc}, which would add a second, competing
 * autoconfiguration module building its own transport-provider bean via a
 * separate toggle mechanism, plus the full Spring MVC stack this server has
 * no use for.
 *
 * <p>All three beans below are {@code @ConditionalOnProperty}-gated off
 * `db2mcp.http.enabled` (default {@code false}): disabled means none of
 * them exist in the context at all, not that they exist and behave as
 * no-ops.
 *
 * <p>{@code stdioServerTransport} in {@code StdioLifecycleConfig} is gated
 * off exactly when this is gated on - see that class's javadoc for why
 * stdio and HTTP genuinely cannot both be active at once in this MCP
 * SDK/Spring AI version.
 *
 * <p>{@code fallbackNotFoundServletRegistration} exists to close a real gap
 * found by {@code docs/security/http-transport-security-review-2026-08-29.md}
 * (SR-001): a servlet-container filter mapped to {@code /*} - like {@code
 * bearerTokenFilterRegistration} below - only actually runs for a request
 * that resolves to *some* registered servlet. Confirmed directly in
 * embedded Tomcat 11: with only {@code /mcp/*} registered, a path like
 * {@code /} or {@code /actuator/health} short-circuited straight to
 * Tomcat's own stock 404, bypassing {@link BearerTokenAuthenticationFilter}
 * entirely - the filter's own log line never fired for those paths, only
 * for {@code /mcp}-prefixed ones. Registering this catch-all servlet at
 * {@code /*} guarantees every path resolves to some servlet (this one, if
 * nothing more specific matches), so the filter chain always runs first -
 * {@code /mcp/*} remains a more specific prefix mapping and still wins for
 * its own paths (Servlet spec §12.2's mapping-precedence rules).
 */
@Configuration
public class HttpTransportConfig {

    @Bean
    @ConditionalOnProperty(prefix = "db2mcp.http", name = "enabled", havingValue = "true")
    public McpStreamableServerTransportProvider httpServerTransport() {
        // Same JacksonMcpJsonMapper(JsonMapper.builder().build()) construction
        // StdioLifecycleConfig already uses, for the same documented reason:
        // Spring AI's own autoconfigured mcpServerJsonMapper bean doesn't
        // reliably instantiate in time in the packaged jar's bootstrap
        // ordering (reproduced directly, not a workaround for a hypothetical).
        return HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(JsonMapper.builder().build()))
                .mcpEndpoint("/mcp")
                .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "db2mcp.http", name = "enabled", havingValue = "true")
    public ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServletRegistration(
            McpStreamableServerTransportProvider transportProvider) {
        return new ServletRegistrationBean<>(
                (HttpServletStreamableServerTransportProvider) transportProvider, "/mcp/*");
    }

    @Bean
    @ConditionalOnProperty(prefix = "db2mcp.http", name = "enabled", havingValue = "true")
    public FilterRegistrationBean<BearerTokenAuthenticationFilter> bearerTokenFilterRegistration(
            HttpTransportProperties properties) {
        FilterRegistrationBean<BearerTokenAuthenticationFilter> registration =
                new FilterRegistrationBean<>(new BearerTokenAuthenticationFilter(properties.bearerToken()));
        // Fail-closed on every path this app exposes, not just /mcp -
        // genuinely true now that fallbackNotFoundServletRegistration below
        // guarantees a servlet always resolves for every path, so this
        // filter's chain always actually runs (see that bean's javadoc for
        // the real gap this closes, SR-001).
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    @ConditionalOnProperty(prefix = "db2mcp.http", name = "enabled", havingValue = "true")
    public ServletRegistrationBean<HttpServlet> fallbackNotFoundServletRegistration() {
        ServletRegistrationBean<HttpServlet> registration = new ServletRegistrationBean<>(
                new HttpServlet() {
                    @Override
                    protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                        // Reached only for a request that got past
                        // bearerTokenFilterRegistration (i.e. carried a
                        // valid token) but doesn't match /mcp/*'s more
                        // specific mapping - a plain 404 is the correct,
                        // honest response, not a security concern in
                        // itself. This servlet's real job is existing at
                        // all, so /* resolves to *some* servlet and the
                        // filter chain runs (see class javadoc, SR-001).
                        resp.sendError(HttpServletResponse.SC_NOT_FOUND);
                    }
                },
                "/*");
        registration.setName("fallbackNotFoundServlet");
        return registration;
    }
}
