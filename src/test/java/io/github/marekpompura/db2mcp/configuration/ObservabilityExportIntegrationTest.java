package io.github.marekpompura.db2mcp.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.marekpompura.db2mcp.application.port.out.CapabilityPort;
import io.github.marekpompura.db2mcp.bootstrap.Db2McpServerApplication;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Boots the real Spring context - the actual autoconfiguration CR-010's
 * fix depends on ({@code OpenTelemetryTracingAutoConfiguration}), not
 * {@code SimpleTracer} the way every other test in this project uses -
 * and proves a real tool call produces a span that a real {@code
 * SpanExporter} bean actually receives (ADR 0030). {@code
 * SimpleTracer}-based unit tests (every other test in this project)
 * prove spans are opened/tagged/closed correctly; they cannot prove
 * export happens at all, since {@code SimpleTracer} bypasses the OTel
 * SDK's exporter pipeline entirely - that gap is exactly what CR-010
 * flagged and exactly what this test closes.
 *
 * <p>The captured span is real production data flowing through the real
 * {@code LoggingSpanExporter} bean too (both exporters receive every
 * span - {@code OpenTelemetryTracingAutoConfiguration} combines all
 * {@code SpanExporter} beans) - this test's {@link CapturingSpanExporter}
 * only adds a way to assert on what was exported, it doesn't replace the
 * production exporter.
 */
// @DirtiesContext: see DynatraceDisabledIntegrationTest's own Javadoc for
// why - accumulated, never-closed @SpringBootTest contexts each holding a
// live non-daemon metrics-publisher thread is what eventually forces
// Surefire to kill its own fork JVM, cutting off JaCoCo's coverage dump.
@SpringBootTest(classes = Db2McpServerApplication.class)
@org.springframework.context.annotation.Import(ObservabilityExportIntegrationTest.CapturingExporterConfig.class)
@DirtiesContext
class ObservabilityExportIntegrationTest {

    // Replaces StdioLifecycleConfig's real stdioServerTransport bean, which
    // wraps System.in in a background reader thread. A real, found bug: that
    // thread blocks in a native read() with nothing in this test ever
    // closing System.in, and Thread.interrupt() cannot unblock a plain
    // stream read - so on JVM shutdown, closing this context's real
    // transport bean hung indefinitely, past Surefire's own 30-second
    // patience, intermittently failing the whole build (not just this
    // test). This test only needs the real tracing/metrics
    // autoconfiguration (what CR-010 is about), not a live MCP transport,
    // so the bean is mocked out entirely - its factory method never runs,
    // and the blocking thread never starts.
    @MockitoBean
    private McpServerTransportProvider mcpServerTransportProvider;

    @DynamicPropertySource
    static void unreachableDatasource(DynamicPropertyRegistry registry) {
        // This test only needs the Spring context and its real tracing
        // autoconfiguration to boot - not a live or H2 Db2 connection.
        // getCapabilities() itself never requires one (ADR 0028's graceful
        // degradation), so a deliberately unreachable host is enough.
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:observability_export_test;DB_CLOSE_DELAY=-1");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("db2mcp.allowed-schemas[0]", () -> "ITSM");
    }

    @TestConfiguration
    static class CapturingExporterConfig {
        @Bean
        CapturingSpanExporter capturingSpanExporter() {
            return new CapturingSpanExporter();
        }
    }

    static class CapturingSpanExporter implements SpanExporter {
        final List<SpanData> exported = new CopyOnWriteArrayList<>();

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            exported.addAll(spans);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    private CapabilityPort capabilityPort;

    @org.springframework.beans.factory.annotation.Autowired
    private CapturingSpanExporter capturingSpanExporter;

    @org.springframework.beans.factory.annotation.Autowired
    private io.opentelemetry.sdk.trace.SdkTracerProvider sdkTracerProvider;

    @Test
    void aRealToolCallProducesASpanThatARealSpanExporterReceives() {
        capabilityPort.capabilities();

        // The SDK's span processor may batch/export asynchronously - force a
        // flush rather than sleeping a guessed duration, then poll briefly for
        // the export callback (also async) to actually populate the list.
        sdkTracerProvider.forceFlush().join(5, java.util.concurrent.TimeUnit.SECONDS);

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(capturingSpanExporter.exported).isNotEmpty());

        assertThat(capturingSpanExporter.exported)
                .anySatisfy(span -> assertThat(span.getName()).isEqualTo("mcp.tool getCapabilities"));
    }
}
