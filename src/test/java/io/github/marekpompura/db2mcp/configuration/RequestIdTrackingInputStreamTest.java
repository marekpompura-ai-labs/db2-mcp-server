package io.github.marekpompura.db2mcp.configuration;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class RequestIdTrackingInputStreamTest {

    @Test
    void marksARequestPendingAsSoonAsItsLineIsFullyRead() throws IOException {
        InFlightRequestTracker tracker = new InFlightRequestTracker();
        String payload = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\"}\n";
        RequestIdTrackingInputStream in = new RequestIdTrackingInputStream(
                new ByteArrayInputStream(payload.getBytes(UTF_8)), tracker);

        readAll(in);

        assertThat(tracker.current()).isEqualTo(1);
    }

    @Test
    void doesNotTrackANotificationLine() throws IOException {
        InFlightRequestTracker tracker = new InFlightRequestTracker();
        String payload = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n";
        RequestIdTrackingInputStream in = new RequestIdTrackingInputStream(
                new ByteArrayInputStream(payload.getBytes(UTF_8)), tracker);

        readAll(in);

        assertThat(tracker.current()).isZero();
    }

    @Test
    void tracksMultipleLinesDeliveredAcrossSeparateReadCalls() throws IOException {
        InFlightRequestTracker tracker = new InFlightRequestTracker();
        String payload = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}\n"
                + "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n"
                + "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\"}\n";
        RequestIdTrackingInputStream in = new RequestIdTrackingInputStream(
                new ByteArrayInputStream(payload.getBytes(UTF_8)), tracker);

        // One byte at a time - the realistic worst case for line
        // reconstruction, since it forces every line boundary to be
        // detected across many separate read() calls rather than within a
        // single buffer.
        int b;
        while ((b = in.read()) != -1) {
            assertThat(b).isNotEqualTo(-1);
        }

        assertThat(tracker.current()).isEqualTo(2);
    }

    @Test
    void toleratesCarriageReturnLineEndings() throws IOException {
        InFlightRequestTracker tracker = new InFlightRequestTracker();
        String payload = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\"}\r\n";
        RequestIdTrackingInputStream in = new RequestIdTrackingInputStream(
                new ByteArrayInputStream(payload.getBytes(UTF_8)), tracker);

        readAll(in);

        assertThat(tracker.current()).isEqualTo(1);
    }

    @Test
    void passesReadBytesThroughUnchanged() throws IOException {
        InFlightRequestTracker tracker = new InFlightRequestTracker();
        byte[] original = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\"}\n".getBytes(UTF_8);
        RequestIdTrackingInputStream in =
                new RequestIdTrackingInputStream(new ByteArrayInputStream(original), tracker);

        assertThat(readAll(in)).isEqualTo(original);
    }

    private static byte[] readAll(RequestIdTrackingInputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16];
        int n;
        while ((n = in.read(buf, 0, buf.length)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
