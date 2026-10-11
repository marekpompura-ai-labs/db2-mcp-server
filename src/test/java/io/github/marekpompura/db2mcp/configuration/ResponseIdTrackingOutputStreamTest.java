package io.github.marekpompura.db2mcp.configuration;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class ResponseIdTrackingOutputStreamTest {

    @Test
    void clearsAPendingRequestAsSoonAsItsResponseLineIsFullyWritten() throws IOException {
        InFlightRequestTracker tracker = new InFlightRequestTracker();
        tracker.markRequested("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\"}".getBytes(UTF_8));
        ByteArrayOutputStream underlying = new ByteArrayOutputStream();
        ResponseIdTrackingOutputStream out = new ResponseIdTrackingOutputStream(underlying, tracker);

        out.write("{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{}}\n".getBytes(UTF_8));

        assertThat(tracker.current()).isZero();
    }

    @Test
    void writingOneByteAtATimeStillDetectsTheCompleteLine() throws IOException {
        InFlightRequestTracker tracker = new InFlightRequestTracker();
        tracker.markRequested("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\"}".getBytes(UTF_8));
        ByteArrayOutputStream underlying = new ByteArrayOutputStream();
        ResponseIdTrackingOutputStream out = new ResponseIdTrackingOutputStream(underlying, tracker);

        for (byte b : "{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{}}\n".getBytes(UTF_8)) {
            out.write(b);
        }

        assertThat(tracker.current()).isZero();
    }

    @Test
    void doesNotDoubleProcessBytesWrittenViaTheArrayOverload() throws IOException {
        // Regression guard: FilterOutputStream's default write(byte[],int,int)
        // loops calling write(int), so routing through super.write(...)
        // instead of the wrapped `out` field directly would run this
        // stream's own observe() twice per byte - still correct for
        // *this* line (idempotent id removal), but see the class Javadoc
        // for why that path is avoided regardless.
        InFlightRequestTracker tracker = new InFlightRequestTracker();
        ByteArrayOutputStream underlying = new ByteArrayOutputStream();
        ResponseIdTrackingOutputStream out = new ResponseIdTrackingOutputStream(underlying, tracker);
        byte[] payload = "{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{}}\n".getBytes(UTF_8);

        out.write(payload);

        assertThat(underlying.toByteArray()).isEqualTo(payload);
    }

    @Test
    void leavesUnrelatedPendingRequestsAlone() throws IOException {
        InFlightRequestTracker tracker = new InFlightRequestTracker();
        tracker.markRequested("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\"}".getBytes(UTF_8));
        tracker.markRequested("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\"}".getBytes(UTF_8));
        ByteArrayOutputStream underlying = new ByteArrayOutputStream();
        ResponseIdTrackingOutputStream out = new ResponseIdTrackingOutputStream(underlying, tracker);

        out.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}\n".getBytes(UTF_8));

        assertThat(tracker.current()).isEqualTo(1);
    }
}
