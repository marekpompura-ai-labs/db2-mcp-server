package io.github.marekpompura.db2mcp.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.marekpompura.db2mcp.adapter.observability.AuditLogger;
import io.github.marekpompura.db2mcp.adapter.observability.MetricsRecorder;
import io.github.marekpompura.db2mcp.adapter.observability.SpanRecorder;
import io.github.marekpompura.db2mcp.domain.error.ErrorCategory;
import io.github.marekpompura.db2mcp.domain.error.McpToolException;
import io.github.marekpompura.db2mcp.policy.capability.CapabilityProfile;
import io.github.marekpompura.db2mcp.policy.concurrency.ConcurrencyLimitsProperties;
import io.github.marekpompura.db2mcp.policy.concurrency.ToolExecutionAdmissionGate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.test.simple.SimpleSpan;
import io.micrometer.tracing.test.simple.SimpleTracer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

class AdmissionControlledToolCallbackTest {

    private static ToolCallback stubDelegate(String toolName, String returnValue) {
        ToolCallback delegate = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(toolName);
        when(delegate.getToolDefinition()).thenReturn(definition);
        when(delegate.call(any())).thenReturn(returnValue);
        return delegate;
    }

    private static AdmissionControlledToolCallback wrap(ToolCallback delegate, int bound) {
        return new AdmissionControlledToolCallback(
                delegate,
                new ToolExecutionAdmissionGate(new ConcurrencyLimitsProperties(bound)),
                CapabilityProfile.SAFE_QUERY,
                new AuditLogger(),
                new MetricsRecorder(new SimpleMeterRegistry()),
                new SpanRecorder(new SimpleTracer()));
    }

    @Test
    void callsWithinTheBoundSucceedAndReturnTheDelegateResult() {
        ToolCallback delegate = stubDelegate("getCapabilities", "{}");
        AdmissionControlledToolCallback callback = wrap(delegate, 2);

        String result = callback.call("{}");

        assertThat(result).isEqualTo("{}");
        verify(delegate).call("{}");
    }

    @Test
    void callsExceedingTheBoundAreRejectedWithoutInvokingTheDelegate() throws Exception {
        CountDownLatch delegateEntered = new CountDownLatch(1);
        CountDownLatch releaseDelegate = new CountDownLatch(1);
        ToolCallback delegate = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("executeQuery");
        when(delegate.getToolDefinition()).thenReturn(definition);
        when(delegate.call(any())).thenAnswer(invocation -> {
            delegateEntered.countDown();
            releaseDelegate.await();
            return "held";
        });
        AdmissionControlledToolCallback callback = wrap(delegate, 1);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> holding = executor.submit(() -> callback.call("holding-input"));
            assertThat(delegateEntered.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> callback.call("rejected-input"))
                    .isInstanceOf(McpToolException.class)
                    .extracting(t -> ((McpToolException) t).category())
                    .isEqualTo(ErrorCategory.POLICY_REJECTED);
            // The held call above legitimately did invoke the delegate (that's
            // what "holding" means) - the property under test is that the
            // REJECTED call's own input never reached it.
            verify(delegate, never()).call("rejected-input");

            releaseDelegate.countDown();
            assertThat(holding.get(5, TimeUnit.SECONDS)).isEqualTo("held");
            verify(delegate, times(1)).call(any());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void permitIsReleasedEvenWhenTheDelegateThrows() {
        ToolCallback delegate = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("executeQuery");
        when(delegate.getToolDefinition()).thenReturn(definition);
        when(delegate.call(any())).thenThrow(new RuntimeException("boom"));
        AdmissionControlledToolCallback callback = wrap(delegate, 1);

        assertThatThrownBy(() -> callback.call("{}")).hasMessage("boom");

        // A fresh call immediately succeeding proves the finally released
        // the permit - if it had leaked, this second call would be
        // rejected with POLICY_REJECTED instead of reaching the delegate.
        // doReturn(...).when(...), not when(...).thenReturn(...): the mock
        // is still stubbed to throw at this point, and when(mock.call(...))
        // would re-invoke that throwing stub immediately while setting up
        // the new one - doReturn(...) never invokes the real method.
        doReturn("ok").when(delegate).call(any());
        assertThat(callback.call("{}")).isEqualTo("ok");
    }

    @Test
    void repeatedCallsWellBeyondTheBoundAllEventuallySucceed_provingNoPermitLeak() {
        ToolCallback delegate = stubDelegate("getCapabilities", "{}");
        AdmissionControlledToolCallback callback = wrap(delegate, 1);

        for (int i = 0; i < 1000; i++) {
            assertThat(callback.call("{}")).isEqualTo("{}");
        }
    }

    // --- ADR 0010/0011/0012: rejection is recorded, not silent --------------

    private ListAppender<ILoggingEvent> auditAppender;

    private ListAppender<ILoggingEvent> attachAuditListAppender() {
        ch.qos.logback.classic.Logger auditLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("AUDIT");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        auditLogger.addAppender(appender);
        this.auditAppender = appender;
        return appender;
    }

    @AfterEach
    void detachAuditListAppender() {
        if (auditAppender != null) {
            ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger("AUDIT")).detachAppender(auditAppender);
        }
    }

    @Test
    void rejectionIsRecordedInAuditLogMetricsAndSpan() {
        ToolCallback delegate = stubDelegate("executeQuery", "{}");
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        SimpleTracer tracer = new SimpleTracer();
        ToolExecutionAdmissionGate gate = new ToolExecutionAdmissionGate(new ConcurrencyLimitsProperties(1));
        AdmissionControlledToolCallback callback = new AdmissionControlledToolCallback(
                delegate, gate, CapabilityProfile.SAFE_QUERY, new AuditLogger(), new MetricsRecorder(meterRegistry),
                new SpanRecorder(tracer));
        ListAppender<ILoggingEvent> appender = attachAuditListAppender();

        assertThat(gate.tryAcquire()).isTrue(); // hold the only permit directly, forcing the next call to reject

        assertThatThrownBy(() -> callback.call("{}")).isInstanceOf(McpToolException.class);

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("CONCURRENCY_LIMIT_EXCEEDED");

        assertThat(meterRegistry.get("mcp.tool.rejected").counter().count()).isEqualTo(1.0);

        assertThat(tracer.getSpans()).isNotEmpty();
        SimpleSpan span = tracer.getSpans().getLast();
        assertThat(span.getTags()).containsEntry("outcome", "rejected");
        assertThat(span.getTags()).containsEntry("reason", "CONCURRENCY_LIMIT_EXCEEDED");
        // P1-3 (2026-09-01 master-brief gap review, #40, ADR 0041): this
        // rejection span used to carry no correlationId either, even though
        // this method already generates one for the thrown McpToolException.
        assertThat(span.getTags()).containsKey("correlationId");
    }
}
