package io.github.marekpompura.db2mcp.configuration;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class InFlightRequestTrackerTest {

    private static byte[] line(String json) {
        return json.getBytes(UTF_8);
    }

    @Test
    void awaitDrainReturnsImmediatelyWhenNothingPending() {
        InFlightRequestTracker tracker = new InFlightRequestTracker();

        assertThat(tracker.awaitDrain(Duration.ofSeconds(5))).isTrue();
    }

    @Test
    void markRequestedTracksARequestWithIdAndMethod() {
        InFlightRequestTracker tracker = new InFlightRequestTracker();

        tracker.markRequested(line("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\"}"));

        assertThat(tracker.current()).isEqualTo(1);
    }

    @Test
    void markRequestedIgnoresANotificationWithNoId() {
        InFlightRequestTracker tracker = new InFlightRequestTracker();

        tracker.markRequested(line("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"));

        assertThat(tracker.current()).isZero();
    }

    @Test
    void markRequestedIgnoresMalformedJson() {
        InFlightRequestTracker tracker = new InFlightRequestTracker();

        tracker.markRequested(line("not json"));

        assertThat(tracker.current()).isZero();
    }

    @Test
    void markRespondedClearsAMatchingPendingId() {
        InFlightRequestTracker tracker = new InFlightRequestTracker();
        tracker.markRequested(line("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\"}"));

        tracker.markResponded(line("{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{}}"));

        assertThat(tracker.current()).isZero();
    }

    @Test
    void markRespondedWithANonMatchingIdLeavesThePendingRequestAlone() {
        InFlightRequestTracker tracker = new InFlightRequestTracker();
        tracker.markRequested(line("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\"}"));

        tracker.markResponded(line("{\"jsonrpc\":\"2.0\",\"id\":99,\"result\":{}}"));

        assertThat(tracker.current()).isEqualTo(1);
    }

    @Test
    void awaitDrainReturnsTrueOnceTheOnlyPendingRequestIsMarkedResponded() throws Exception {
        InFlightRequestTracker tracker = new InFlightRequestTracker();
        tracker.markRequested(line("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\"}"));

        CountDownLatch drainStarted = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        var drained = pool.submit(() -> {
            drainStarted.countDown();
            return tracker.awaitDrain(Duration.ofSeconds(5));
        });
        assertThat(drainStarted.await(2, TimeUnit.SECONDS)).isTrue();

        // Give awaitDrain a moment to actually enter its poll loop before the
        // response arrives - otherwise this is a race against the assertion
        // itself, not evidence of correct behavior.
        Thread.sleep(50);
        tracker.markResponded(line("{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{}}"));

        assertThat(drained.get(5, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();
    }

    @Test
    void awaitDrainTimesOutIfAResponseNeverArrives() {
        InFlightRequestTracker tracker = new InFlightRequestTracker();
        tracker.markRequested(line("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\"}"));

        long start = System.nanoTime();
        boolean drained = tracker.awaitDrain(Duration.ofMillis(100));
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertThat(drained).isFalse();
        assertThat(tracker.current()).isEqualTo(1);
        assertThat(elapsedMs).isGreaterThanOrEqualTo(100);
    }

    @Test
    void currentReflectsMultipleOverlappingPendingRequests() {
        InFlightRequestTracker tracker = new InFlightRequestTracker();

        tracker.markRequested(line("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\"}"));
        tracker.markRequested(line("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\"}"));
        assertThat(tracker.current()).isEqualTo(2);

        tracker.markResponded(line("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}"));
        assertThat(tracker.current()).isEqualTo(1);

        tracker.markResponded(line("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{}}"));
        assertThat(tracker.current()).isZero();
    }
}
