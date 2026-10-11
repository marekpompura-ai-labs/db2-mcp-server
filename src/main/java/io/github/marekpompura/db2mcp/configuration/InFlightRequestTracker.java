package io.github.marekpompura.db2mcp.configuration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Tracks which JSON-RPC request ids are awaiting a response, by id rather
 * than by a count of in-flight calls (ADR 0031). A request's id is marked
 * pending the instant its line is read off stdin by {@link
 * RequestIdTrackingInputStream} - synchronously, in the same {@code read()}
 * call the SDK itself observes those bytes in - and cleared the instant a
 * response line carrying the same id is written to stdout by {@link
 * ResponseIdTrackingOutputStream}. Both marks happen directly on the byte
 * stream, not on a downstream tool dispatch, so there is no scheduler
 * handoff window whose duration has to be guessed at with a fixed delay -
 * the predecessor of this class ({@code InFlightToolCallTracker}) needed
 * exactly such a delay, and it was still observed, empirically, to be too
 * tight under real CI load even after being widened once already.
 */
@Component
public class InFlightRequestTracker {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Set<String> pendingIds = ConcurrentHashMap.newKeySet();

    /**
     * Marks {@code line}'s id as pending, if it's a JSON-RPC request (has
     * both {@code id} and {@code method}). A notification (no {@code id})
     * never gets a response by protocol definition and is correctly left
     * untracked.
     */
    void markRequested(byte[] line) {
        JsonNode node = parse(line);
        if (node != null && node.has("method") && node.has("id")) {
            pendingIds.add(node.get("id").asText());
        }
    }

    /**
     * Clears {@code line}'s id from pending, if present. Every complete
     * line this server writes to stdout is a response to some earlier
     * request (this server never initiates its own server-to-client
     * requests), so any line carrying an {@code id} is exactly the signal
     * that request is no longer in flight.
     */
    void markResponded(byte[] line) {
        JsonNode node = parse(line);
        if (node != null && node.has("id")) {
            pendingIds.remove(node.get("id").asText());
        }
    }

    private JsonNode parse(byte[] line) {
        if (line.length == 0) {
            return null;
        }
        try {
            return objectMapper.readTree(line);
        } catch (IOException e) {
            // Not a parseable JSON-RPC line - nothing to track from it.
            // Malformed input/output is the SDK's own concern to reject,
            // not this tracker's to validate.
            return null;
        }
    }

    public int current() {
        return pendingIds.size();
    }

    /**
     * Blocks until every request id marked pending has a matching response
     * marked, or {@code timeout} elapses. Returns {@code false} on timeout
     * (a request never got a response) - the caller decides what "still
     * pending after the grace period" means for shutdown.
     */
    public boolean awaitDrain(Duration timeout) {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (!pendingIds.isEmpty()) {
            if (System.nanoTime() >= deadlineNanos) {
                return false;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }
}
