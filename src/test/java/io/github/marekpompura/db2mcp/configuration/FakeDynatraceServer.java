package io.github.marekpompura.db2mcp.configuration;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A local, ephemeral-port stand-in for Dynatrace's ingest endpoints, used
 * by the Dynatrace export tests (thread 5, ADR 0036) - a real Dynatrace
 * tenant isn't reachable from this environment, and {@code
 * com.sun.net.httpserver.HttpServer} (JDK-builtin) is enough to capture
 * and assert the request shape (path, headers, body) without adding a new
 * test dependency like WireMock.
 */
final class FakeDynatraceServer {

    private final HttpServer server;
    private final List<CapturedRequest> tracesRequests = new CopyOnWriteArrayList<>();
    private final List<CapturedRequest> metricsRequests = new CopyOnWriteArrayList<>();

    record CapturedRequest(String method, String path, Map<String, List<String>> headers, byte[] body) {

        String header(String name) {
            List<String> values = headers().get(name);
            return values == null || values.isEmpty() ? null : values.get(0);
        }

        String bodyAsLatin1() {
            // Not a claim that the body IS Latin-1 text (the traces body is
            // OTLP protobuf, binary) - Latin-1 maps every byte value to
            // exactly one char with no loss or re-encoding, so a plain
            // substring search for an ASCII value (e.g. a span/metric name)
            // still works correctly against this decoding either way.
            return new String(body(), StandardCharsets.ISO_8859_1);
        }
    }

    FakeDynatraceServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/api/v2/otlp/v1/traces", exchange -> handle(exchange, tracesRequests));
        server.createContext("/api/v2/metrics/ingest", exchange -> handle(exchange, metricsRequests));
        server.start();
    }

    private static void handle(HttpExchange exchange, List<CapturedRequest> sink) {
        try {
            byte[] body = exchange.getRequestBody().readAllBytes();
            sink.add(new CapturedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    new ConcurrentHashMap<>(exchange.getRequestHeaders()),
                    body));
            exchange.sendResponseHeaders(202, -1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            exchange.close();
        }
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    List<CapturedRequest> tracesRequests() {
        return tracesRequests;
    }

    List<CapturedRequest> metricsRequests() {
        return metricsRequests;
    }

    void stop() {
        server.stop(0);
    }
}
