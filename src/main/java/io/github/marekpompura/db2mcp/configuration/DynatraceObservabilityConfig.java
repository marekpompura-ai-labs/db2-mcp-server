package io.github.marekpompura.db2mcp.configuration;

import io.github.marekpompura.db2mcp.adapter.observability.DynatraceExportProperties;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.dynatrace.DynatraceApiVersion;
import io.micrometer.dynatrace.DynatraceConfig;
import io.micrometer.dynatrace.DynatraceMeterRegistry;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Opt-in Dynatrace export (thread 5, ADR 0036) - a sibling of, not a
 * replacement for, {@link ObservabilityExportConfig}'s stderr-only logging
 * export. Deliberately kept in a separate class: {@link
 * ObservabilityExportConfig} is {@code Accepted} (ADR 0030), trivially
 * simple, and has no conditionals or credential-adjacent logic; folding
 * in credential-handling, network-configuring, conditionally-registered
 * beans there would change its risk profile for no benefit.
 *
 * <p>Both {@code @Bean} methods are {@code @ConditionalOnProperty}-gated -
 * when {@code db2mcp.dynatrace.enabled=false} (the default), neither
 * factory method ever executes, so no Dynatrace bean exists in the
 * context at all, not merely a bean that behaves as a no-op. Fail-fast
 * validation of {@code environmentUrl}/{@code apiToken} when enabled
 * happens separately, in {@link
 * io.github.marekpompura.db2mcp.adapter.observability.DynatraceExportPropertiesValidator}
 * - by the time these bean methods run, those values are already known
 * to be present and well-formed.
 *
 * <p>When enabled, both beans register additively alongside {@link
 * ObservabilityExportConfig}'s existing beans, not in place of them -
 * confirmed directly from Spring Boot 4.1.0's own tracing autoconfiguration
 * bytecode, whose {@code spanProcessors(ObjectProvider<SpanProcessor>)}
 * bean collects every {@code SpanProcessor} bean in the context via {@code
 * orderedStream().toList()}; Micrometer's well-established {@code
 * CompositeMeterRegistry} mechanism does the equivalent for {@code
 * MeterRegistry} beans. This is deliberate, not incidental: ADR 0030's own
 * rationale (a demonstrable, always-inspectable local record of what was
 * exported) matters doubly here, since this environment cannot verify a
 * live Dynatrace round-trip - the stderr lines stay the only verification
 * path actually available in this session.
 */
@Configuration
public class DynatraceObservabilityConfig {

    private static final String TRACES_PATH = "/api/v2/otlp/v1/traces";
    private static final String METRICS_PATH = "/api/v2/metrics/ingest";

    /**
     * {@code SimpleSpanProcessor}, not a bare {@code SpanExporter} bean
     * auto-wrapped in Spring's default {@code BatchSpanProcessor} - the
     * exact same reasoning as {@link ObservabilityExportConfig#loggingSpanProcessor()}:
     * this server's graceful shutdown (ADR 0016) exits promptly once its
     * MCP client disconnects, and an async batch processor's pending spans
     * are not confirmed flushed before process exit.
     *
     * <p>{@code "Api-Token " + apiToken} - Dynatrace's OTLP ingest requires
     * this exact header value format, not {@code "Bearer "}, confirmed
     * directly against Dynatrace's own documentation. Dynatrace's OTLP
     * endpoint accepts HTTP only, no gRPC, matching {@link
     * OtlpHttpSpanExporter}'s choice over a gRPC exporter variant.
     */
    @Bean
    @ConditionalOnProperty(prefix = "db2mcp.dynatrace", name = "enabled", havingValue = "true")
    public SpanProcessor dynatraceSpanProcessor(DynatraceExportProperties properties) {
        SpanExporter exporter = OtlpHttpSpanExporter.builder()
                .setEndpoint(properties.environmentUrl() + TRACES_PATH)
                .addHeader("Authorization", "Api-Token " + properties.apiToken())
                .build();
        return SimpleSpanProcessor.create(exporter);
    }

    /**
     * A {@link DynatraceMeterRegistry}, Micrometer's native Dynatrace
     * integration against Dynatrace's Metrics API v2 - deliberately not the
     * generic OTLP metrics exporter, which would require explicitly
     * configuring delta aggregation temporality (Dynatrace's OTLP metrics
     * endpoint rejects OTel's default cumulative temporality; this path
     * avoids that entirely by not routing metrics through OTLP at all).
     *
     * <p>{@link DynatraceConfig#uri()} is used as the complete, literal
     * ingest URL by Dynatrace's own exporter code - {@code
     * properties.environmentUrl()} is a bare host with no path (see {@link
     * DynatraceExportProperties}'s javadoc), so the correct API path is
     * appended here, once, rather than trusting every caller of {@code
     * environment-url} to remember the right suffix themselves.
     */
    @Bean
    @ConditionalOnProperty(prefix = "db2mcp.dynatrace", name = "enabled", havingValue = "true")
    public MeterRegistry dynatraceMeterRegistry(DynatraceExportProperties properties) {
        DynatraceConfig config = new DynatraceConfig() {
            @Override
            public String get(String key) {
                return null; // every value this registry needs is provided by an override below
            }

            @Override
            public String apiToken() {
                return properties.apiToken();
            }

            @Override
            public String uri() {
                return properties.environmentUrl() + METRICS_PATH;
            }

            @Override
            public DynatraceApiVersion apiVersion() {
                return DynatraceApiVersion.V2;
            }
        };
        return DynatraceMeterRegistry.builder(config).clock(Clock.SYSTEM).build();
    }
}
