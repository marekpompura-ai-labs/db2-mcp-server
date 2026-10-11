package io.github.marekpompura.db2mcp.adapter.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Genuine OpenTelemetry spans via Micrometer Tracing's Tracer/Span API
 * (ADR 0012), not the higher-level Observation API - keeps ADR 0011's
 * already-Accepted MetricsRecorder untouched, per that ADR's decision
 * driver to avoid forcing a rewrite of already-shipped work.
 */
@Component
public class SpanRecorder {

    private final Tracer tracer;

    public SpanRecorder(Tracer tracer) {
        this.tracer = tracer;
    }

    public Span startRoot(String name) {
        return tracer.nextSpan().name(name).start();
    }

    /**
     * {@code Tracer.nextSpan(Span)} is annotated {@code @Nullable} on its
     * return value, not only on its parameter (verified against
     * micrometer-tracing 1.4.5's bytecode; issue #83, found by SpotBugs
     * 4.10). When a tracer declines to create a child span, the work still
     * runs - untraced. Observability going dark is an acceptable
     * degradation; observability taking down every instrumented tool call
     * with an NPE is not, and this sits on the path of all of them.
     */
    public <T> T inChildSpan(Span parent, String name, Supplier<T> work) {
        Span nextSpan = tracer.nextSpan(parent);
        if (nextSpan == null) {
            return work.get();
        }
        Span child = nextSpan.name(name).start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(child)) {
            return work.get();
        } catch (RuntimeException e) {
            child.error(e);
            throw e;
        } finally {
            child.end();
        }
    }

    public void inChildSpan(Span parent, String name, Runnable work) {
        inChildSpan(parent, name, () -> {
            work.run();
            return null;
        });
    }
}
