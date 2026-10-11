package io.github.marekpompura.db2mcp.adapter.db2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.marekpompura.db2mcp.domain.model.BoundedList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for the payload bound itself (#62, 2026-09-09 ChatGPT
 * re-review, RR-P0-1). The adapters' own integration tests prove the bound
 * end to end against real result sets; these prove the properties the
 * adapters rely on - largest-fitting-prefix, not merely some fitting
 * prefix, and fail-closed on both "nothing fits" and "cannot be measured".
 */
class PayloadBudgetTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CORRELATION_ID = "11111111-1111-1111-1111-111111111111";

    private static BoundedList<String> assemble(List<String> kept, boolean truncated) {
        return BoundedList.of(kept, truncated, CORRELATION_ID);
    }

    @Test
    void wholeResponseIsReturnedUntouchedWhenItFits() {
        List<String> items = List.of("a", "b", "c");

        PayloadBudget.Fitted<BoundedList<String>> fitted =
                PayloadBudget.fit(items, false, 100_000, PayloadBudgetTest::assemble);

        assertThat(fitted.result().items()).containsExactly("a", "b", "c");
        assertThat(fitted.truncated()).isFalse();
        assertThat(fitted.result().truncated()).isFalse();
    }

    @Test
    void anEarlierRowCapTruncationIsCarriedThroughEvenWhenTheResponseFits() {
        PayloadBudget.Fitted<BoundedList<String>> fitted =
                PayloadBudget.fit(List.of("a"), true, 100_000, PayloadBudgetTest::assemble);

        assertThat(fitted.truncated()).isTrue();
        assertThat(fitted.result().truncated()).isTrue();
    }

    @Test
    void admitsTheLargestFittingPrefixNotMerelyAFittingOne() throws Exception {
        List<String> items = IntStream.range(0, 200).mapToObj(i -> "item-" + i).toList();
        int budget = 900;

        PayloadBudget.Fitted<BoundedList<String>> fitted =
                PayloadBudget.fit(items, false, budget, PayloadBudgetTest::assemble);

        assertThat(MAPPER.writeValueAsBytes(fitted.result()).length).isLessThanOrEqualTo(budget);
        assertThat(fitted.truncated()).isTrue();
        // One more item must not have fit - otherwise the search stopped short.
        int keptCount = fitted.result().items().size();
        assertThat(keptCount).isPositive();
        BoundedList<String> oneMore = assemble(items.subList(0, keptCount + 1), true);
        assertThat(MAPPER.writeValueAsBytes(oneMore).length).isGreaterThan(budget);
    }

    @Test
    void measuredLengthIsTheLengthOfWhatIsActuallyReturned() throws Exception {
        List<String> items = IntStream.range(0, 50).mapToObj(i -> "item-" + i).toList();

        PayloadBudget.Fitted<BoundedList<String>> fitted =
                PayloadBudget.fit(items, false, 400, PayloadBudgetTest::assemble);

        assertThat(fitted.byteLength()).isEqualTo(MAPPER.writeValueAsBytes(fitted.result()).length);
    }

    @Test
    void neverClaimsTruncationWhileReturningEveryElement() throws Exception {
        // R2-P2-1 (2026-09-17 re-review): "truncated":true serializes one
        // byte shorter than "truncated":false. With the budget sitting
        // exactly between the two, an earlier version of the search could
        // re-admit the complete element list under the truncated=true flag
        // and return everything while claiming something was dropped.
        List<String> items = IntStream.range(0, 12).mapToObj(i -> "item-" + i).toList();
        int wholeUntruncated = MAPPER.writeValueAsBytes(assemble(items, false)).length;
        int wholeTruncated = MAPPER.writeValueAsBytes(assemble(items, true)).length;

        // Guard the premise: if these ever stop differing by exactly the
        // false/true byte, this test is no longer exercising the boundary.
        assertThat(wholeUntruncated - wholeTruncated).isEqualTo(1);

        PayloadBudget.Fitted<BoundedList<String>> fitted =
                PayloadBudget.fit(items, false, wholeTruncated, PayloadBudgetTest::assemble);

        assertThat(fitted.result().items()).hasSizeLessThan(items.size());
        assertThat(MAPPER.writeValueAsBytes(fitted.result()).length).isLessThanOrEqualTo(wholeTruncated);
    }

    @Test
    void anAlreadyTruncatedWholeListIsNotReadmittedByTheSearchEither() throws Exception {
        // The mirror case: the row cap already dropped rows, so truncated
        // is legitimately true - but the whole list was measured with that
        // flag before the search began, so the search must not retry it.
        List<String> items = IntStream.range(0, 12).mapToObj(i -> "item-" + i).toList();
        int wholeTruncated = MAPPER.writeValueAsBytes(assemble(items, true)).length;

        PayloadBudget.Fitted<BoundedList<String>> fitted =
                PayloadBudget.fit(items, true, wholeTruncated - 1, PayloadBudgetTest::assemble);

        assertThat(fitted.result().items()).hasSizeLessThan(items.size());
        assertThat(MAPPER.writeValueAsBytes(fitted.result()).length).isLessThanOrEqualTo(wholeTruncated - 1);
    }

    @Test
    void failsClosedWhenNotEvenAnEmptyResponseFits() {
        // The envelope's own correlationId alone is longer than this budget:
        // the old model would have returned that envelope anyway and called
        // the cap enforced.
        assertThatThrownBy(() -> PayloadBudget.fit(List.of("a"), false, 10, PayloadBudgetTest::assemble))
                .isInstanceOf(PayloadBudget.BudgetExceededException.class);
    }

    @Test
    void emptyInputStillProducesAMeasuredResponseWhenTheEnvelopeFits() throws Exception {
        PayloadBudget.Fitted<BoundedList<String>> fitted =
                PayloadBudget.fit(List.of(), false, 1000, PayloadBudgetTest::assemble);

        assertThat(fitted.result().items()).isEmpty();
        assertThat(fitted.result().rowCount()).isZero();
        assertThat(fitted.truncated()).isFalse();
        assertThat(fitted.byteLength()).isEqualTo(MAPPER.writeValueAsBytes(fitted.result()).length);
    }

    @Test
    void multibyteAndEscapedContentIsMeasuredAtItsRealSerializedSize() throws Exception {
        // "é" is two UTF-8 bytes and a quote/backslash becomes two
        // characters once escaped - a character-count model understates all
        // three, which is how a response gets over its own cap.
        List<String> items = IntStream.range(0, 100).mapToObj(i -> "éé\"\\" + i).toList();
        int budget = 700;

        PayloadBudget.Fitted<BoundedList<String>> fitted =
                PayloadBudget.fit(items, false, budget, PayloadBudgetTest::assemble);

        assertThat(MAPPER.writeValueAsBytes(fitted.result()).length).isLessThanOrEqualTo(budget);
    }

    @Test
    void singleObjectResultsAreRefusedRatherThanTrimmedWhenOverBudget() {
        assertThatThrownBy(() -> PayloadBudget.requireWithinBudget("a".repeat(500), 100))
                .isInstanceOf(PayloadBudget.BudgetExceededException.class);
    }

    @Test
    void singleObjectResultsPassThroughWhenWithinBudget() {
        PayloadBudget.Fitted<String> fitted = PayloadBudget.requireWithinBudget("small", 100);

        assertThat(fitted.result()).isEqualTo("small");
        assertThat(fitted.truncated()).isFalse();
    }

    @Test
    void metricSizingReturnsTheLengthWhenMeasurable() {
        assertThat(PayloadBudget.byteLengthIfMeasurable("abc")).hasValue(5); // "abc" with quotes
    }

    @Test
    void metricSizingIsEmptyRatherThanThrowingWhenUnmeasurable() {
        // The distinction this method exists for (#94): enforcing a bound
        // must fail closed, recording a metric must not fail at all. The
        // caller that must never throw gets an empty value, not an
        // exception it would have to catch and discard silently.
        Object unserializable = new Object() {
            @SuppressWarnings("unused")
            public Object getSelf() {
                throw new IllegalStateException("boom");
            }
        };

        assertThat(PayloadBudget.byteLengthIfMeasurable(unserializable)).isEmpty();
    }

    @Test
    void unmeasurableValuesFailClosedInsteadOfCountingAsZero() {
        // The predecessor returned 0 here, which counted an unmeasurable
        // value as free and let it straight through the budget check.
        Object unserializable = new Object() {
            @SuppressWarnings("unused")
            public Object getSelf() {
                throw new IllegalStateException("boom");
            }
        };

        assertThatThrownBy(() -> PayloadBudget.byteLength(unserializable))
                .isInstanceOf(PayloadBudget.SizingException.class);
    }
}
