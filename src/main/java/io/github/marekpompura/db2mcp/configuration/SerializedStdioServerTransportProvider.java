package io.github.marekpompura.db2mcp.configuration;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransport;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * A drop-in replacement for the MCP Java SDK's own {@code
 * StdioServerTransportProvider} (GitHub issue #29, found during ADR 0033's
 * live-DB2 verification). Already a known, filed bug upstream -
 * <a href="https://github.com/modelcontextprotocol/java-sdk/issues/686">
 * modelcontextprotocol/java-sdk#686</a> is the identical server-side bug
 * (same stack trace, open since 2026-02-20), with a stalled, unmerged fix
 * attempt at
 * <a href="https://github.com/modelcontextprotocol/java-sdk/pull/834">PR
 * #834</a> (a different approach - {@code emitNext()} with a
 * busy-looping retry on the same sink, rather than bypassing it - see ADR
 * 0031 for the comparison). Revisit this class if/when an official fix
 * ships in a released SDK version; until then this project owns keeping
 * it in sync with the SDK's own interface evolution.
 *
 * <p><b>The bug this replaces:</b> the SDK's own implementation writes every
 * outbound response through a single {@code Sinks.many().unicast()} sink
 * shared by the whole stdio session - a Reactor sink type documented as safe
 * for only one producer thread at a time. Two tool calls that happen to
 * complete at close to the same instant (confirmed live: 100% reproducible
 * when two calls to the identical tool race, and even under a global,
 * server-wide lock around tool execution - the actual write race happens in
 * an async continuation downstream of {@code ToolCallback.call()} returning,
 * a thread hop this project's own code has no hook into) can have two
 * different threads call that sink's {@code tryEmitNext} concurrently, which
 * that sink type doesn't support - one call's response is silently dropped
 * with a {@code RuntimeException("Failed to enqueue message")}.
 *
 * <p><b>The fix:</b> don't use a Reactor sink for outbound messages at all.
 * {@link #sendMessage} writes directly, synchronously, under a plain {@code
 * synchronized} lock - the same technique the SDK's own implementation
 * already (correctly) uses for the raw {@code OutputStream.write} call
 * itself, just applied one layer earlier so there is no unsynchronized
 * enqueue step left to race on. Concurrent callers simply queue on the lock
 * and each write completes in turn; there is no failure mode where a message
 * is silently lost, because there is no separate "enqueue, then someone else
 * dequeues and writes" step at all.
 *
 * <p>Inbound processing (a single dedicated reader thread, deserializing
 * newline-delimited JSON-RPC messages and dispatching to {@link
 * McpServerSession#handle}) mirrors the SDK's own approach - that side of
 * the SDK's implementation was never the problem, only the outbound sink
 * was. This class also fully preserves every layer this project already
 * built around whatever {@link InputStream}/{@link OutputStream} it's
 * given: {@link RequestIdTrackingInputStream}/{@link
 * ResponseIdTrackingOutputStream} (ADR 0031) observe raw bytes as they cross
 * these streams regardless of which class reads/writes them, and {@link
 * EofTriggeredShutdownInputStream}'s EOF-triggered shutdown callback fires
 * from inside this class's own blocking {@code read()} loop exactly as it
 * did inside the SDK's - nothing about that mechanism depends on which
 * transport class owns the read loop.
 */
public class SerializedStdioServerTransportProvider implements McpServerTransportProvider {

    private static final Logger logger = LoggerFactory.getLogger(SerializedStdioServerTransportProvider.class);

    private final McpJsonMapper jsonMapper;
    private final InputStream inputStream;
    private final OutputStream outputStream;
    private final AtomicBoolean isClosing = new AtomicBoolean(false);
    private final Object writeLock = new Object();

    private volatile McpServerSession session;

    public SerializedStdioServerTransportProvider(
            McpJsonMapper jsonMapper, InputStream inputStream, OutputStream outputStream) {
        this.jsonMapper = jsonMapper;
        this.inputStream = inputStream;
        this.outputStream = outputStream;
    }

    @Override
    public void setSessionFactory(McpServerSession.Factory sessionFactory) {
        StdioTransport transport = new StdioTransport();
        this.session = sessionFactory.create(transport);
        startInboundProcessing(transport);
    }

    @Override
    public Mono<Void> notifyClients(String method, Object params) {
        if (this.session == null) {
            return Mono.error(new IllegalStateException("No session to notify"));
        }
        return this.session.sendNotification(method, params)
                .doOnError(e -> logger.error("Failed to send notification: {}", e.getMessage()));
    }

    @Override
    public Mono<Void> closeGracefully() {
        if (this.session == null) {
            return Mono.empty();
        }
        return this.session.closeGracefully();
    }

    /**
     * Reads newline-delimited JSON-RPC messages from {@code inputStream}
     * until EOF, dispatching each to {@code session.handle(...)}. Not a
     * daemon thread - this project's process is meant to stay alive for as
     * long as its stdio session is, matching the SDK's own original
     * behavior; {@link StdioLifecycleConfig}'s shutdown wiring is what
     * actually ends the process, not this thread being a daemon.
     */
    private void startInboundProcessing(StdioTransport transport) {
        Thread inboundThread = new Thread(
                () -> {
                    try {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
                        String line;
                        while (!isClosing.get() && (line = reader.readLine()) != null) {
                            if (isClosing.get()) {
                                break;
                            }
                            handleLine(line);
                        }
                    } catch (IOException e) {
                        if (!isClosing.get()) {
                            logger.error("Error reading from stdin", e);
                        }
                    } catch (Exception e) {
                        if (!isClosing.get()) {
                            logger.error("Error in inbound processing", e);
                        }
                    } finally {
                        isClosing.set(true);
                        if (session != null) {
                            session.close();
                        }
                    }
                },
                "stdio-inbound");
        inboundThread.setDaemon(false);
        inboundThread.start();
    }

    private void handleLine(String line) {
        McpSchema.JSONRPCMessage message;
        try {
            message = McpSchema.deserializeJsonRpcMessage(jsonMapper, line);
        } catch (Exception e) {
            logger.error("Error processing inbound message", e);
            return;
        }
        session.handle(message)
                .subscribe(
                        v -> {},
                        error -> logger.error("Error handling message: {}", error.getMessage(), error));
    }

    /** Session-scoped transport - {@link #sendMessage} is this class's whole reason to exist; see the class Javadoc. */
    private final class StdioTransport implements McpServerTransport {

        @Override
        public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
            return Mono.<Void>fromRunnable(() -> writeMessage(message));
        }

        private void writeMessage(McpSchema.JSONRPCMessage message) {
            if (isClosing.get()) {
                return;
            }
            String jsonMessage;
            try {
                jsonMessage = jsonMapper.writeValueAsString(message);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            // Escape any embedded newlines in the JSON message as per spec -
            // matches the SDK's own framing exactly.
            jsonMessage = jsonMessage.replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n");
            try {
                synchronized (writeLock) {
                    outputStream.write(jsonMessage.getBytes(StandardCharsets.UTF_8));
                    outputStream.write('\n');
                    outputStream.flush();
                }
            } catch (IOException e) {
                if (!isClosing.get()) {
                    throw new UncheckedIOException(e);
                }
            }
        }

        @Override
        public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
            return jsonMapper.convertValue(data, typeRef);
        }

        @Override
        public Mono<Void> closeGracefully() {
            return Mono.fromRunnable(() -> isClosing.set(true));
        }

        @Override
        public void close() {
            isClosing.set(true);
        }
    }
}
