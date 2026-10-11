package io.github.marekpompura.db2mcp.adapter.db2;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.OptionalLong;
import java.util.function.BiFunction;

/**
 * Enforces {@code maxPayloadBytes} against the <em>complete serialized
 * application response</em> (#62, 2026-09-09 ChatGPT re-review, RR-P0-1),
 * replacing {@code JsonPayloadSizer}'s per-row content budget.
 *
 * <p>The predecessor summed individually serialized rows and explicitly
 * excluded the enclosing envelope - column descriptors, wrapper keys, the
 * correlation ID, the commas between accumulated rows, the outer
 * array/object syntax. A response could therefore exceed the configured
 * limit while every individual row was inside it, which made the setting a
 * row-content budget rather than the deterministic maximum response size
 * the master brief asks for.
 *
 * <p>This class instead measures candidate responses as they will actually
 * be serialized, and admits the largest prefix of elements whose fully
 * assembled response fits. Nothing is estimated and no envelope overhead is
 * reserved by guesswork: the returned object is the one that was measured.
 * The search is O(log n) serializations of an already row-capped list.
 *
 * <p>Both failure modes fail <em>closed</em>. A response whose envelope
 * alone exceeds the budget (very wide column metadata, say) raises {@link
 * BudgetExceededException} rather than being sent anyway, and a
 * serialization failure raises {@link SizingException} rather than the
 * predecessor's silent {@code return 0}, which counted an unmeasurable
 * value as free.
 */
final class PayloadBudget {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PayloadBudget() {}

    /**
     * Serialized byte length of {@code value} as it would cross the MCP
     * boundary. Fails closed: an unmeasurable value is an error, never zero.
     */
    static long byteLength(Object value) {
        try {
            return MAPPER.writeValueAsBytes(value).length;
        } catch (JsonProcessingException e) {
            throw new SizingException(e);
        }
    }

    /**
     * Serialized byte length for <em>metrics</em>, where an unmeasurable
     * value must not become the reason a call fails - empty instead of
     * thrown.
     *
     * <p>Deliberately a separate method rather than a flag on {@link
     * #byteLength}: the two callers want opposite things, and that belongs
     * in the signature rather than in a comment. Enforcing a bound must
     * fail closed, because sending a response nobody could measure defeats
     * the bound. Recording a metric must not fail at all, because losing an
     * observability data point is not worth failing a call over.
     *
     * <p>This is not a return to the fail-open {@code return 0} the
     * 2026-09-09 review rejected. That value was fed straight into a budget
     * check, so an unmeasurable response counted as free and passed. Here
     * there is no check to evade - an empty result means no metric is
     * recorded, and nothing is admitted that would otherwise have been
     * refused.
     */
    static OptionalLong byteLengthIfMeasurable(Object value) {
        try {
            return OptionalLong.of(byteLength(value));
        } catch (SizingException e) {
            return OptionalLong.empty();
        }
    }

    /**
     * Assembles the largest prefix of {@code elements} whose complete
     * serialized response fits within {@code maxPayloadBytes}.
     *
     * @param alreadyTruncated whether an earlier stage (the row cap) had
     *     already dropped elements - the assembled response reports {@code
     *     truncated} as the disjunction of that and any dropping done here
     * @param assembler builds the real response record from a kept prefix
     *     and the truncation flag, so what gets measured is exactly what
     *     gets returned - envelope, correlation ID and all
     * @throws BudgetExceededException if not even an empty response fits
     */
    static <E, R> Fitted<R> fit(
            List<E> elements, boolean alreadyTruncated, int maxPayloadBytes, BiFunction<List<E>, Boolean, R> assembler) {
        R whole = assembler.apply(elements, alreadyTruncated);
        long wholeBytes = byteLength(whole);
        if (wholeBytes <= maxPayloadBytes) {
            return new Fitted<>(whole, wholeBytes, alreadyTruncated);
        }
        // Binary search the largest prefix that fits, bounded at size-1.
        //
        // size itself is never a legitimate candidate here (#92, R2-P2-1 of
        // the 2026-09-17 re-review). Whichever flag the caller passed, the
        // whole list was already measured above: if alreadyTruncated was
        // true, assembler(elements, true) is exactly what failed; if it was
        // false, then assembler(elements, true) would keep every element
        // while reporting truncated=true - a response that claims to have
        // dropped something it did not, purely to reclaim the one byte
        // between "false" and "true". An earlier version of this search
        // used size as the bound and could return precisely that.
        int low = 0;
        int high = elements.size() - 1;
        R best = null;
        long bestBytes = -1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            R candidate = assembler.apply(elements.subList(0, mid), true);
            long bytes = byteLength(candidate);
            if (bytes <= maxPayloadBytes) {
                best = candidate;
                bestBytes = bytes;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        if (best == null) {
            throw new BudgetExceededException(maxPayloadBytes);
        }
        return new Fitted<>(best, bestBytes, true);
    }

    /**
     * Admits a response that has no list to trim - {@code describeProcedure}
     * and {@code getProcedureSource}, whose size is what it is. There is no
     * partial answer to fall back to, so an over-budget result is refused
     * rather than sent: the alternative is silently breaking the bound for
     * exactly the two tools most likely to carry a large blob of DDL.
     *
     * @throws BudgetExceededException if the result exceeds the budget
     */
    static <R> Fitted<R> requireWithinBudget(R result, int maxPayloadBytes) {
        long bytes = byteLength(result);
        if (bytes > maxPayloadBytes) {
            throw new BudgetExceededException(maxPayloadBytes);
        }
        return new Fitted<>(result, bytes, false);
    }

    /** A measured response and the byte length that was actually measured. */
    record Fitted<R>(R result, long byteLength, boolean truncated) {}

    /** The configured budget cannot accommodate this response at all. */
    static final class BudgetExceededException extends RuntimeException {
        private final int maxPayloadBytes;

        BudgetExceededException(int maxPayloadBytes) {
            super("Response exceeds the configured maxPayloadBytes of " + maxPayloadBytes);
            this.maxPayloadBytes = maxPayloadBytes;
        }

        int maxPayloadBytes() {
            return maxPayloadBytes;
        }
    }

    /** A response could not be serialized, so its size is unknowable. */
    static final class SizingException extends RuntimeException {
        SizingException(Throwable cause) {
            super("Could not measure response size", cause);
        }
    }
}
