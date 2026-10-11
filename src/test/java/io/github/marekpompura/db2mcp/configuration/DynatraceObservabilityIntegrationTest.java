package io.github.marekpompura.db2mcp.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.marekpompura.db2mcp.adapter.observability.DynatraceExportPropertiesValidator;
import io.github.marekpompura.db2mcp.application.port.out.CapabilityPort;
import io.github.marekpompura.db2mcp.bootstrap.Db2McpServerApplication;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Boots the real Spring context with Dynatrace export enabled and pointed
 * at {@link FakeDynatraceServer} (thread 5, ADR 0036) - follows {@link
 * ObservabilityExportIntegrationTest}'s exact established pattern
 * (real tracing/metrics autoconfiguration, mocked stdio transport, H2
 * datasource). {@link DynatraceObservabilityConfigTest} already proves the
 * bean factory methods work in isolation; this proves the real,
 * conditionally-registered beans actually wire up and receive a real tool
 * call's telemetry.
 *
 * <p>{@code @DirtiesContext}: see {@link DynatraceDisabledIntegrationTest}'s
 * own Javadoc for why - this context's real {@code DynatraceMeterRegistry}
 * publisher thread is the same non-daemon-accumulation risk.
 */
@SpringBootTest(classes = Db2McpServerApplication.class)
@DirtiesContext
class DynatraceObservabilityIntegrationTest {

    private static FakeDynatraceServer server;

    @MockitoBean
    private McpServerTransportProvider mcpServerTransportProvider;

    // This test's fake Dynatrace server is plain HTTP (no cert-management
    // complexity for a local test double) - real Dynatrace tenants are
    // always https, so DynatraceExportPropertiesValidator correctly
    // requires it (see DynatraceExportPropertiesValidatorTest for that
    // check's own coverage). Mocked out here since it isn't what this
    // test verifies (bean wiring/real export plumbing), the same reasoning
    // ObservabilityExportIntegrationTest already applies to mocking out
    // the unrelated stdio transport bean.
    @MockitoBean
    private DynatraceExportPropertiesValidator dynatraceExportPropertiesValidator;

    @Autowired
    private CapabilityPort capabilityPort;

    @Autowired
    private SdkTracerProvider sdkTracerProvider;

    @BeforeAll
    static void startFakeServer() {
        server = new FakeDynatraceServer();
    }

    @AfterAll
    static void stopFakeServer() {
        server.stop();
    }

    @DynamicPropertySource
    static void dynatraceEnabledAgainstFakeServer(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.datasource.url",
                () -> "jdbc:h2:mem:dynatrace_observability_test;DB_CLOSE_DELAY=-1");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("db2mcp.allowed-schemas[0]", () -> "ITSM");
        registry.add("db2mcp.dynatrace.enabled", () -> "true");
        registry.add("db2mcp.dynatrace.environment-url", () -> server.baseUrl());
        registry.add("db2mcp.dynatrace.api-token", () -> TestSentinel.TOKEN);
    }

    static final class TestSentinel {
        static final String TOKEN = "dt0c01.INTEGRATION_TEST_SENTINEL_TOKEN";
    }

    @Test
    void aRealToolCall_isExportedToBothLoggingAndDynatraceSimultaneously() {
        capabilityPort.capabilities();

        sdkTracerProvider.forceFlush().join(5, TimeUnit.SECONDS);

        // getCapabilities() produces two spans (a db.execute child and the
        // mcp.tool getCapabilities parent), and SimpleSpanProcessor exports
        // each individually as its own HTTP POST - the child span ends (and
        // so exports) before its parent, so the getCapabilities span is not
        // reliably the first request captured. Wait for both, then search
        // by content rather than assume an index.
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(server.tracesRequests()).hasSizeGreaterThanOrEqualTo(2));

        assertThat(server.tracesRequests())
                .allSatisfy(r -> assertThat(r.path()).isEqualTo("/api/v2/otlp/v1/traces"))
                .anySatisfy(r -> assertThat(r.bodyAsLatin1()).contains("mcp.tool getCapabilities"));
    }

    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void attachLogAppender() {
        logAppender = new ListAppender<>();
        logAppender.start();
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
                .addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
                .detachAppender(logAppender);
    }

    @Test
    void apiTokenNeverAppearsInApplicationLogsDuringARealExportedCall() {
        capabilityPort.capabilities();
        sdkTracerProvider.forceFlush().join(5, TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(server.tracesRequests()).isNotEmpty());

        List<String> logLines =
                logAppender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(logLines).noneMatch(line -> line.contains(TestSentinel.TOKEN));
    }
}
