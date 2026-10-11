package io.github.marekpompura.db2mcp.configuration.http;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rejects every HTTP request that doesn't carry the configured bearer token
 * (thread 6, ADR 0037) - a plain {@link Filter}, not {@code
 * OncePerRequestFilter} (which lives in {@code spring-web}, deliberately
 * kept off this project's classpath, see {@code pom.xml}'s comment on
 * {@code spring-boot-starter-tomcat}), guaranteed by the servlet spec to run
 * before {@code service()} for every HTTP verb - so no MCP/tool logic ever
 * runs for an unauthenticated request.
 *
 * <p>Missing header, malformed scheme, and wrong token are all rejected
 * identically (401, generic body) - there is deliberately no way for a
 * caller to distinguish "no token" from "wrong token" from the response,
 * so there's no oracle to probe. The rejection response is plain HTTP, not
 * an MCP-protocol error ({@code McpToolException}/{@code ErrorCategory}):
 * this runs before any MCP session exists or any JSON-RPC message is
 * parsed, so there is no tool name or correlation ID to attach - forcing it
 * into that taxonomy would be exactly the kind of layer violation ADR 0018
 * exists to prevent in the other direction.
 */
// final: the constructor below throws on an unusable token, and SpotBugs'
// CT_CONSTRUCTOR_THROW correctly warns that a throwing constructor leaves a
// partially-initialized object open to a finalizer attack. That attack needs
// a malicious subclass overriding finalize(); a final class cannot be
// subclassed, so the vulnerability is removed rather than suppressed. No
// exclusion is added - ADR 0046's reasoning about exclusions silencing the
// *next* finding at a line applies with particular force on this one.
public final class BearerTokenAuthenticationFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(BearerTokenAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    /**
     * Floor, not a recommendation - {@code config/.env.example} recommends
     * {@code openssl rand -hex 32}. Declared here rather than in {@link
     * HttpTransportPropertiesValidator} because this filter is now the
     * earliest enforcement point, and both places must agree.
     */
    static final int MIN_BEARER_TOKEN_LENGTH = 16;

    private final byte[] expectedTokenBytes;

    /**
     * @throws HttpTransportConfigurationException if the token is blank or
     *     shorter than {@link #MIN_BEARER_TOKEN_LENGTH}
     */
    public BearerTokenAuthenticationFilter(String bearerToken) {
        // Checked HERE, at bean construction, and not only in
        // HttpTransportPropertiesValidator (HTTP-P1-1/HTTP-P1-2, 2026-09-21
        // dedicated HTTP review). The validator is an ApplicationRunner, and
        // Spring Boot starts the embedded servlet container during context
        // refresh - runners execute afterwards. So with enabled=true and a
        // blank token, Tomcat had already bound 127.0.0.1:8080 before the
        // validator threw.
        //
        // That window mattered because of the second half of the defect: an
        // empty expected token makes MessageDigest.isEqual(empty, empty)
        // return true, so `Authorization: Bearer ` (empty credential) would
        // have authenticated every request while the port was open.
        //
        // A constructor throw fails bean creation, which aborts the refresh
        // before the web server ever starts. The port is never bound, so
        // there is no window and no blank-token filter can exist.
        if (bearerToken == null || bearerToken.isBlank()) {
            // Only the property NAME appears in the message, never the value -
            // same discipline as HttpTransportPropertiesValidator's messages.
            throw new HttpTransportConfigurationException("Property: db2mcp.http.bearer-token / Reason: blank - "
                    + "the HTTP transport cannot be gated by an empty token, which would authenticate every "
                    + "request; set DB2MCP_HTTP_BEARER_TOKEN or set db2mcp.http.enabled=false.");
        }
        if (bearerToken.length() < MIN_BEARER_TOKEN_LENGTH) {
            throw new HttpTransportConfigurationException("Property: db2mcp.http.bearer-token / Reason: token is "
                    + bearerToken.length() + " characters, shorter than the required minimum of "
                    + MIN_BEARER_TOKEN_LENGTH + " - generate one with e.g. `openssl rand -hex 32`.");
        }
        this.expectedTokenBytes = bearerToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        if (isAuthorized(httpRequest.getHeader("Authorization"))) {
            chain.doFilter(request, response);
            return;
        }

        log.warn(
                "Rejected unauthenticated HTTP request: method={} path={} remoteAddr={}",
                httpRequest.getMethod(),
                httpRequest.getRequestURI(),
                httpRequest.getRemoteAddr());
        httpResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        httpResponse.setHeader("WWW-Authenticate", "Bearer");
        httpResponse.setContentType("application/json");
        httpResponse.getWriter().write("{\"error\":\"unauthorized\"}");
    }

    private boolean isAuthorized(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            return false;
        }
        byte[] presentedTokenBytes =
                authorizationHeader.substring(BEARER_PREFIX.length()).getBytes(StandardCharsets.UTF_8);
        // MessageDigest.isEqual, not String.equals: a constant-time
        // comparison avoids a timing side-channel that could otherwise
        // help an attacker recover the token byte-by-byte. This is an
        // implementation guarantee asserted by code review, not something
        // the test suite measures via timing (a timing-based test would be
        // flaky/environment-dependent) - see BearerTokenAuthenticationFilterTest
        // for the functional coverage instead (correct/wrong/missing/malformed).
        return MessageDigest.isEqual(expectedTokenBytes, presentedTokenBytes);
    }
}
