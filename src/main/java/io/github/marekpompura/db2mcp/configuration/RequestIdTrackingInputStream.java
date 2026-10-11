package io.github.marekpompura.db2mcp.configuration;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Marks a JSON-RPC request's id as pending in {@link InFlightRequestTracker}
 * the instant its line is fully read off the wrapped stream - synchronously,
 * inside the same {@code read()} call the SDK itself observes those bytes
 * in (ADR 0031). This is what removes the fixed dispatch-settle delay the
 * previous version of this fix needed: there is no scheduler handoff to
 * wait out here, because marking happens on the raw bytes as they cross
 * this stream, not on downstream tool dispatch.
 *
 * <p>Wrap this stream first (innermost), then {@link
 * EofTriggeredShutdownInputStream} around it. A single {@code read()} call
 * physically cannot both deliver the trailing bytes of a line and return
 * EOF - {@link InputStream}'s contract only ever returns -1 once zero bytes
 * are available - so a request line's id is always marked before any later
 * {@code read()} call can possibly observe the EOF that follows it.
 */
class RequestIdTrackingInputStream extends FilterInputStream {

    private final InFlightRequestTracker tracker;
    private final ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream(256);

    RequestIdTrackingInputStream(InputStream in, InFlightRequestTracker tracker) {
        super(in);
        this.tracker = tracker;
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b != -1) {
            observe((byte) b);
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = super.read(b, off, len);
        for (int i = 0; i < n; i++) {
            observe(b[off + i]);
        }
        return n;
    }

    private void observe(byte b) {
        if (b == '\n') {
            tracker.markRequested(lineBuffer.toByteArray());
            lineBuffer.reset();
        } else if (b != '\r') {
            lineBuffer.write(b);
        }
    }
}
