package io.github.marekpompura.db2mcp.configuration;

import io.micrometer.core.instrument.logging.LoggingMeterRegistry;
import io.opentelemetry.exporter.logging.LoggingSpanExporter;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A demonstrable local export path for both metrics and spans (CR-010,
 * 2026-08-14 code review) - closing the gap the review found: {@code
 * MeterRegistry} and {@code Tracer} beans existed and were genuinely
 * instrumented (ADR 0011, ADR 0012), but nothing proved either one's data
 * actually reached anywhere observable. "Instrumented" and "exported and
 * observable" were, correctly, two different claims until this class
 * existed.
 *
 * <p>The review's own suggested remediation offers a choice - "a
 * localhost-only management endpoint with Prometheus metrics" or "OTLP
 * export to a local collector/Jaeger, or an explicitly configured logging
 * exporter bean." The first option is a network listener, which
 * contradicts ADR 0001's foundational, repeatedly-reverified property
 * (re-checked via {@code lsof} in ADR 0011, ADR 0012, ADR 0015, ADR 0019)
 * that this server opens no network port of any kind. A local collector
 * (Jaeger/OTLP) is also not available in this environment (ADR 0009's
 * same reasoning that blocks live Db2 access applies equally here - no
 * external service is reachable). The logging-exporter path the review
 * itself names as an acceptable alternative is therefore the only option
 * consistent with this project's own architecture, applied symmetrically
 * to both metrics and traces rather than only to spans as the review's
 * wording suggested.
 *
 * <p>Both write to stderr like every other log line (ADR 0001 - stdout
 * is JSON-RPC only), verified directly, not assumed.
 */
@Configuration
public class ObservabilityExportConfig {

    /**
     * A {@code SpanProcessor} bean, not a bare {@code SpanExporter} bean -
     * Spring Boot's OpenTelemetry tracing autoconfiguration auto-detects
     * both and would otherwise wrap a plain {@code SpanExporter} bean in its
     * own default {@code BatchSpanProcessor}, which batches asynchronously
     * on a several-second delay. A real, found-and-fixed gap: this server's
     * graceful shutdown (ADR 0016) closes the Spring context and exits
     * promptly once its MCP client disconnects, and the batch processor's
     * pending spans were never actually confirmed flushed before process
     * exit - metrics (via {@code LoggingMeterRegistry}, a genuine {@code
     * PushMeterRegistry} that does flush on {@code close()}) reliably
     * appeared in stderr, but spans silently did not, verified directly
     * against the real packaged jar, not assumed. {@link
     * SimpleSpanProcessor} exports synchronously, per span, on {@code end()}
     * - no batching delay to race against shutdown, and no cost that
     * matters at this server's actual call volume (a governed internal
     * tool, not a high-throughput service).
     */
    @Bean
    public SpanProcessor loggingSpanProcessor() {
        return SimpleSpanProcessor.create(LoggingSpanExporter.create());
    }

    /**
     * Not auto-configured by Spring Boot (unlike Prometheus/OTLP metrics
     * export, which have their own starters) - Micrometer's {@code
     * LoggingMeterRegistry} is a plain bean that joins the same {@code
     * CompositeMeterRegistry} {@link io.github.marekpompura.db2mcp.adapter.observability.MetricsRecorder}
     * already writes to, so every existing {@code mcp.tool.calls}/{@code
     * mcp.tool.duration}/{@code db2.query.duration}/etc. meter is exported
     * through it automatically, with no change to {@code MetricsRecorder}
     * itself.
     */
    @Bean
    public LoggingMeterRegistry loggingMeterRegistry() {
        return new LoggingMeterRegistry();
    }
}
