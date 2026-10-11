package io.github.marekpompura.db2mcp.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.marekpompura.db2mcp.adapter.observability.DynatraceExportProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Plain unit tests (no Spring context) for {@link DynatraceObservabilityConfig}'s
 * two {@code @Bean} factory methods, run directly against {@link
 * FakeDynatraceServer} - a real Dynatrace tenant isn't reachable from this
 * environment (thread 5, ADR 0036).
 */
class DynatraceObservabilityConfigTest {

    private static final String TOKEN = "dt0c01.TEST_TOKEN_VALUE";

    private FakeDynatraceServer server;
    private DynatraceObservabilityConfig config;
    private DynatraceExportProperties properties;

    @BeforeEach
    void setUp() {
        server = new FakeDynatraceServer();
        config = new DynatraceObservabilityConfig();
        properties = new DynatraceExportProperties(true, server.baseUrl(), TOKEN);
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    private static Tracer tracerUsing(SpanProcessor processor) {
        SdkTracerProvider provider =
                SdkTracerProvider.builder().addSpanProcessor(processor).build();
        return provider.get("dynatrace-config-test");
    }

    // SimpleSpanProcessor invokes the exporter synchronously per span, but
    // the exporter's own HTTP call (opentelemetry-exporter-otlp's internal
    // client) completes asynchronously on its own I/O thread - a real
    // network characteristic, not a test artifact, so assertions on the
    // captured request must poll rather than assume it has already arrived
    // the instant end() returns.
    private static List<FakeDynatraceServer.CapturedRequest> awaitAtLeastOneRequest(
            List<FakeDynatraceServer.CapturedRequest> requests) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (requests.isEmpty() && Instant.now().isBefore(deadline)) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return requests;
    }

    @Test
    void dynatraceSpanProcessor_postsToCorrectTracesPath() {
        SpanProcessor processor = config.dynatraceSpanProcessor(properties);

        tracerUsing(processor).spanBuilder("test-span").startSpan().end();

        List<FakeDynatraceServer.CapturedRequest> requests = awaitAtLeastOneRequest(server.tracesRequests());
        assertThat(requests).hasSize(1);
        FakeDynatraceServer.CapturedRequest request = requests.get(0);
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/api/v2/otlp/v1/traces");
    }

    @Test
    void dynatraceSpanProcessor_sendsApiTokenAuthorizationHeader_notBearer() {
        SpanProcessor processor = config.dynatraceSpanProcessor(properties);

        tracerUsing(processor).spanBuilder("test-span").startSpan().end();

        String authHeader =
                awaitAtLeastOneRequest(server.tracesRequests()).get(0).header("Authorization");
        assertThat(authHeader).isEqualTo("Api-Token " + TOKEN);
        assertThat(authHeader).doesNotStartWith("Bearer ");
    }

    @Test
    void dynatraceSpanProcessor_bodyContainsTheExportedSpanName() {
        SpanProcessor processor = config.dynatraceSpanProcessor(properties);

        tracerUsing(processor).spanBuilder("a-distinctive-span-name").startSpan().end();

        // The OTLP body is binary protobuf, and this project has no
        // protobuf-java/generated-stub dependency to fully decode it - a
        // raw substring search for the span name's UTF-8 bytes (decoded as
        // Latin-1, which preserves a 1:1 byte<->char mapping) is enough to
        // confirm the real span name reached the request body, without
        // adding a new dependency just for this one assertion.
        assertThat(awaitAtLeastOneRequest(server.tracesRequests()).get(0).bodyAsLatin1())
                .contains("a-distinctive-span-name");
    }

    @Test
    void dynatraceMeterRegistry_postsToCorrectMetricsIngestPath() {
        MeterRegistry registry = config.dynatraceMeterRegistry(properties);
        registry.counter("test.metric").increment();

        registry.close(); // StepMeterRegistry.close() forces a final publish

        assertThat(server.metricsRequests()).hasSizeGreaterThanOrEqualTo(1);
        FakeDynatraceServer.CapturedRequest request = server.metricsRequests().get(0);
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/api/v2/metrics/ingest");
    }

    @Test
    void dynatraceMeterRegistry_sendsApiTokenAuthorizationHeader_notBearer() {
        MeterRegistry registry = config.dynatraceMeterRegistry(properties);
        registry.counter("test.metric").increment();

        registry.close();

        String authHeader = server.metricsRequests().get(0).header("Authorization");
        assertThat(authHeader).isEqualTo("Api-Token " + TOKEN);
        assertThat(authHeader).doesNotStartWith("Bearer ");
    }

    @Test
    void dynatraceMeterRegistry_bodyContainsTheExpectedMetricName() {
        MeterRegistry registry = config.dynatraceMeterRegistry(properties);
        registry.counter("a.distinctive.metric.name").increment();

        registry.close();

        assertThat(server.metricsRequests().get(0).bodyAsLatin1()).contains("a.distinctive.metric.name");
    }

    // Per this project's own practice (ADR 0028/0030/0035): this test was
    // confirmed to genuinely detect a leak before being trusted - a
    // deliberate System.err.println(TOKEN) was added temporarily to
    // DynatraceObservabilityConfig, this test was re-run and observed to
    // fail, then the line was reverted. Not left in the test itself (there
    // is nothing to assert about a fix that no longer exists), documented
    // here instead.
    @Test
    void neitherExporter_everLogsTheApiToken() {
        ListAppender<ILoggingEvent> logAppender = new ListAppender<>();
        logAppender.start();
        ch.qos.logback.classic.Logger rootLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        rootLogger.addAppender(logAppender);

        ByteArrayOutputStream capturedErr = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(capturedErr, true, StandardCharsets.UTF_8));
        try {
            SpanProcessor processor = config.dynatraceSpanProcessor(properties);
            tracerUsing(processor).spanBuilder("span").startSpan().end();
            awaitAtLeastOneRequest(server.tracesRequests());

            MeterRegistry registry = config.dynatraceMeterRegistry(properties);
            registry.counter("metric").increment();
            registry.close();
        } finally {
            System.setErr(originalErr);
            rootLogger.detachAppender(logAppender);
        }

        String capturedErrText = capturedErr.toString(StandardCharsets.UTF_8);
        assertThat(capturedErrText).doesNotContain(TOKEN);
        List<String> logLines = logAppender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(logLines).noneMatch(line -> line.contains(TOKEN));
    }
}
