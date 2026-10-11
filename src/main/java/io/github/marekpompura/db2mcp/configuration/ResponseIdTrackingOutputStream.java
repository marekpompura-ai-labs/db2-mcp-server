package io.github.marekpompura.db2mcp.configuration;

import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Marks a JSON-RPC response's id as no longer pending in {@link
 * InFlightRequestTracker} the instant its line is fully written to the
 * wrapped stream (ADR 0031) - the outbound counterpart to {@link
 * RequestIdTrackingInputStream}. Only response lines ever cross this stream
 * (this server never initiates its own server-to-client requests), so any
 * complete line carrying an {@code id} is exactly the signal that a pending
 * request has now genuinely been handed to the SDK's own output stream -
 * not merely that the tool method that produced it returned.
 *
 * <p>Both {@code write} overrides write straight to the wrapped {@code out}
 * field rather than through {@code super.write(...)}: {@link
 * FilterOutputStream}'s own multi-byte {@code write(byte[], int, int)}
 * default implementation loops calling {@code write(int)} per byte, so
 * routing through it here - on top of this class's own per-byte
 * {@code observe} loop - would double-process every byte written via the
 * array overload.
 */
class ResponseIdTrackingOutputStream extends FilterOutputStream {

    private final InFlightRequestTracker tracker;
    private final ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream(256);

    ResponseIdTrackingOutputStream(OutputStream out, InFlightRequestTracker tracker) {
        super(out);
        this.tracker = tracker;
    }

    @Override
    public void write(int b) throws IOException {
        out.write(b);
        observe((byte) b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        out.write(b, off, len);
        for (int i = 0; i < len; i++) {
            observe(b[off + i]);
        }
    }

    private void observe(byte b) {
        if (b == '\n') {
            tracker.markResponded(lineBuffer.toByteArray());
            lineBuffer.reset();
        } else if (b != '\r') {
            lineBuffer.write(b);
        }
    }
}
