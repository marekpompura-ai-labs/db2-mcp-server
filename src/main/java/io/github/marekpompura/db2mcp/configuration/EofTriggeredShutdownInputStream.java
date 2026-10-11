package io.github.marekpompura.db2mcp.configuration;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs a callback the first time a read from the wrapped stream returns EOF
 * (ADR 0016). Used to wrap {@code System.in} for the MCP stdio transport, so
 * the process notices when its client closes the pipe - {@link
 * SerializedStdioServerTransportProvider} (this project's own replacement
 * for the MCP SDK's {@code StdioServerTransportProvider}, GitHub issue #29)
 * already detects this internally and closes its protocol session, but
 * nothing ties that to the JVM actually exiting.
 */
class EofTriggeredShutdownInputStream extends FilterInputStream {

    private final Runnable onEof;
    private final AtomicBoolean triggered = new AtomicBoolean(false);

    EofTriggeredShutdownInputStream(InputStream in, Runnable onEof) {
        super(in);
        this.onEof = onEof;
    }

    @Override
    public int read() throws IOException {
        int result = super.read();
        if (result == -1) {
            fireOnce();
        }
        return result;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int result = super.read(b, off, len);
        if (result == -1) {
            fireOnce();
        }
        return result;
    }

    private void fireOnce() {
        if (triggered.compareAndSet(false, true)) {
            onEof.run();
        }
    }
}
