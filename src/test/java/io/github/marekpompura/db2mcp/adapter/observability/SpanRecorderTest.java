package io.github.marekpompura.db2mcp.adapter.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.test.simple.SimpleSpan;
import io.micrometer.tracing.test.simple.SimpleTracer;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * Issue #83: {@code Tracer.nextSpan(Span)} declares {@code @Nullable} on its
 * return value, and {@code inChildSpan} dereferenced it unconditionally -
 * a latent NPE on the path of every instrumented tool call, caught by
 * SpotBugs 4.10 and invisible to 4.9.
 */
class SpanRecorderTest {

    /** A tracer that declines to create child spans, which the API permits. */
    private static final class NoChildSpanTracer extends SimpleTracer {
        @Override
        public SimpleSpan nextSpan(Span parent) {
            return null;
        }
    }

    @Test
    void runsTheWorkUntracedWhenTheTracerDeclinesAChildSpan() {
        SpanRecorder recorder = new SpanRecorder(new NoChildSpanTracer());
        Span parent = new SimpleTracer().nextSpan().name("parent").start();

        String result = recorder.inChildSpan(parent, "child", () -> "work ran");

        assertThat(result).isEqualTo("work ran");
    }

    @Test
    void runsTheRunnableOverloadUntracedToo() {
        SpanRecorder recorder = new SpanRecorder(new NoChildSpanTracer());
        Span parent = new SimpleTracer().nextSpan().name("parent").start();
        AtomicBoolean ran = new AtomicBoolean();

        recorder.inChildSpan(parent, "child", () -> ran.set(true));

        assertThat(ran).isTrue();
    }

    @Test
    void stillPropagatesFailuresWhenUntraced() {
        // Degrading to untraced must not also swallow errors - the work's
        // own failure is the caller's business either way.
        SpanRecorder recorder = new SpanRecorder(new NoChildSpanTracer());
        Span parent = new SimpleTracer().nextSpan().name("parent").start();

        assertThatThrownBy(() -> recorder.inChildSpan(parent, "child", () -> {
                    throw new IllegalStateException("boom");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");
    }

    @Test
    void tracesNormallyWhenTheTracerDoesCreateAChildSpan() {
        SimpleTracer tracer = new SimpleTracer();
        SpanRecorder recorder = new SpanRecorder(tracer);
        Span parent = tracer.nextSpan().name("parent").start();

        String result = recorder.inChildSpan(parent, "child", () -> "work ran");

        assertThat(result).isEqualTo("work ran");
        assertThat(tracer.getSpans()).extracting(s -> s.getName()).contains("child");
    }
}
