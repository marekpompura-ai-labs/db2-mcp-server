package io.github.marekpompura.db2mcp.configuration.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.marekpompura.db2mcp.bootstrap.Db2McpServerApplication;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * Boots the real HTTP transport prototype (thread 6, ADR 0037) with a real
 * embedded servlet container on a random port and proves the bearer-token
 * gate works end-to-end - both with a raw HTTP probe, and with the MCP
 * SDK's own client-side {@link HttpClientStreamableHttpTransport}
 * performing a genuine {@code initialize} handshake plus a real tool call.
 * The latter is materially stronger evidence than a bare HTTP probe: it
 * proves interop with an actual MCP protocol implementation, not just "the
 * servlet returns 200."
 *
 * <p>No {@code @MockitoBean McpServerTransportProvider} here - unlike every
 * other {@code @SpringBootTest} in this project, {@code
 * db2mcp.http.enabled=true} means {@code StdioLifecycleConfig}'s stdio bean
 * is {@code @ConditionalOnProperty}-gated off entirely, so its background
 * stdin-reading thread (the usual reason to mock it out) never starts in
 * the first place.
 *
 * <p>{@code spring.main.web-application-type} is set via {@code
 * @TestPropertySource}, not {@code @DynamicPropertySource} below - a real,
 * independently-found timing gotcha, distinct from thread 5's {@code
 * SpringApplicationBuilder.properties(Map)} bug: {@code
 * @DynamicPropertySource} values are added to the environment via an
 * {@code ApplicationContextInitializer} callback, which runs during {@code
 * prepareContext()} - strictly *after* {@code SpringApplication}'s {@code
 * prepareEnvironment()} step has already bound {@code
 * spring.main.web-application-type} (still application.yml's default
 * {@code none} at that point) onto the application's {@code
 * WebApplicationType}. By the time the dynamic property lands, the web
 * application type decision has already been made and the embedded
 * servlet container never starts - confirmed directly: an earlier version
 * of this test that only used {@code @DynamicPropertySource} failed with
 * {@code local.server.port} never resolving. {@code @TestPropertySource}
 * properties are folded into the environment before {@code
 * SpringApplication.run()} is even invoked, so they're seen by {@code
 * prepareEnvironment()} in time. This is purely a Spring Boot Test
 * bootstrap-ordering quirk - real production startup (a single {@code
 * SpringApplication.run(args)} reading the fully-resolved environment once)
 * is unaffected; {@code DB2MCP_HTTP_WEB_APPLICATION_TYPE=servlet} as a real
 * env var works correctly there, as already reasoned through directly
 * against {@code SpringApplication}'s real bootstrap sequence.
 */
// @DirtiesContext: this context also boots a real embedded Tomcat
// (RANDOM_PORT) alongside the usual non-daemon metrics-publisher thread -
// see DynatraceDisabledIntegrationTest's own Javadoc for the full
// accumulated-context/Surefire-kill/JaCoCo-data-loss mechanism this
// avoids.
@SpringBootTest(classes = Db2McpServerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "spring.main.web-application-type=servlet")
@DirtiesContext
class HttpTransportIntegrationTest {

    private static final String BEARER_TOKEN = "test-bearer-token-" + UUID.randomUUID();

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void httpEnabled(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:http_transport_test;DB_CLOSE_DELAY=-1");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("db2mcp.allowed-schemas[0]", () -> "ITSM");
        // spring.main.web-application-type is NOT set here - see the class
        // javadoc for why @DynamicPropertySource is too late for that
        // specific property; it's set via @TestPropertySource instead.
        registry.add("db2mcp.http.enabled", () -> "true");
        registry.add("db2mcp.http.bearer-token", () -> BEARER_TOKEN);
    }

    private String mcpEndpoint() {
        return "http://127.0.0.1:" + port + "/mcp";
    }

    @Test
    void rawHttpClient_withNoOrWrongToken_isRejectedWith401AndNeverLeaksTheToken() throws Exception {
        // try-with-resources: HttpClient is AutoCloseable since JDK 21
        // specifically because an unclosed client can leave its internal
        // keep-alive/selector threads alive as non-daemon threads (a real,
        // found cause of Surefire force-killing its own fork JVM after
        // tests complete, which in turn cuts off JaCoCo's coverage dump -
        // see DynatraceDisabledIntegrationTest's Javadoc for the fuller
        // mechanism this is one contributor to).
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> noTokenResponse = client.send(
                    HttpRequest.newBuilder(URI.create(mcpEndpoint()))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(noTokenResponse.statusCode()).isEqualTo(401);
            assertThat(noTokenResponse.body()).doesNotContain(BEARER_TOKEN);

            HttpResponse<String> wrongTokenResponse = client.send(
                    HttpRequest.newBuilder(URI.create(mcpEndpoint()))
                            .header("Authorization", "Bearer wrong-token")
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(wrongTokenResponse.statusCode()).isEqualTo(401);
            assertThat(wrongTokenResponse.body()).doesNotContain(BEARER_TOKEN);
        }
    }

    @Test
    void rawHttpClient_hittingAnUnmappedPath_isStillRejectedByTheFilter_notSilentlyBypassed() throws Exception {
        // SR-001 (docs/security/http-transport-security-review-2026-08-29.md):
        // a /*-mapped servlet-container filter only actually runs for a
        // request that resolves to SOME registered servlet - confirmed
        // directly against a running packaged jar that, before
        // HttpTransportConfig.fallbackNotFoundServletRegistration existed,
        // a path with no matching servlet (e.g. this one) short-circuited
        // straight to Tomcat's own stock 404, bypassing
        // BearerTokenAuthenticationFilter entirely. This test proves that
        // gap stays closed: an arbitrary unmapped path, without a token,
        // must still be rejected with 401 (the filter engaging), not 404
        // (the filter being skipped).
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/some-unmapped-path"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.body()).doesNotContain(BEARER_TOKEN);
        }
    }

    @Test
    void realMcpClient_withCorrectToken_performsAGenuineHandshakeAndToolCall() {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(mcpEndpoint())
                .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer " + BEARER_TOKEN))
                .build();

        try (McpSyncClient mcpClient = McpClient.sync(transport).build()) {
            mcpClient.initialize();

            McpSchema.CallToolResult result =
                    mcpClient.callTool(new McpSchema.CallToolRequest("getCapabilities", Map.of()));

            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        }
    }

    @Test
    void realMcpClient_withWrongToken_failsToInitialize() {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(mcpEndpoint())
                .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer wrong-token"))
                .build();

        try (McpSyncClient mcpClient = McpClient.sync(transport).build()) {
            // Confirmed directly (not assumed): the SDK's LifecycleInitializer
            // surfaces this as a plain RuntimeException with no wrapped cause
            // exposed via getCause() - not the more specific
            // McpHttpClientTransportAuthorizationException/McpTransportException
            // an earlier version of this assertion assumed. What matters for
            // this test is proven either way: the 401 genuinely reaches the
            // client and genuinely prevents a working session, which is what
            // the raw-HTTP-client test above already confirms at the
            // transport-status-code level; this test adds proof that a real
            // MCP client, not just a bare HTTP probe, also can't complete
            // initialization with the wrong token.
            assertThatThrownBy(mcpClient::initialize)
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("failed to initialize");
        }
    }
}
