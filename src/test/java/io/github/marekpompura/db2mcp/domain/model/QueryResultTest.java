package io.github.marekpompura.db2mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Issue #67 (RR-P2-1, 2026-09-09 re-review): the compact constructor copied
 * the outer rows list but not each inner row, so the record was immutable in
 * name only - a caller could still mutate {@code result.rows().get(0)}.
 */
class QueryResultTest {

    private static final List<QueryColumn> COLUMNS =
            List.of(new QueryColumn("ID", "INTEGER"), new QueryColumn("NAME", "VARCHAR"));

    private static QueryResult resultWith(List<List<Object>> rows) {
        return new QueryResult(COLUMNS, rows, rows.size(), false, "cid");
    }

    @Test
    void innerRowsAreNotMutableThroughTheAccessor() {
        QueryResult result = resultWith(List.of(new ArrayList<>(List.of(1, "one"))));

        assertThatThrownBy(() -> result.rows().get(0).add("injected"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void innerRowsAreNotMutableThroughTheOriginalListEither() {
        // The caller keeps a reference to the list it passed in - defensive
        // copying only helps if the copy is genuinely detached.
        List<Object> callersRow = new ArrayList<>(List.of(1, "one"));
        QueryResult result = resultWith(new ArrayList<>(List.of(callersRow)));

        callersRow.set(1, "mutated after construction");

        assertThat(result.rows().get(0)).containsExactly(1, "one");
    }

    @Test
    void outerRowListRemainsImmutable() {
        QueryResult result = resultWith(new ArrayList<>(List.of(new ArrayList<>(List.of(1, "one")))));

        assertThatThrownBy(() -> result.rows().add(List.of(2, "two")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void columnsRemainImmutable() {
        QueryResult result = resultWith(List.of(List.of(1, "one")));

        assertThatThrownBy(() -> result.columns().add(new QueryColumn("X", "CHAR")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nullCellsSurviveTheDefensiveCopy() {
        // The reason this is not List.copyOf per row: Db2ValueNormalizer
        // emits null for SQL NULL, and List.copyOf rejects null elements.
        // Copying that way would turn every NULL-bearing result into an NPE -
        // a far worse defect than the mutability this fix addresses.
        List<Object> rowWithNull = new ArrayList<>(Arrays.asList(1, null));

        QueryResult result = resultWith(List.of(rowWithNull));

        assertThat(result.rows().get(0)).containsExactly(1, null);
    }

    @Test
    void anEmptyResultStillConstructs() {
        QueryResult result = resultWith(List.of());

        assertThat(result.rows()).isEmpty();
        assertThat(result.rowCount()).isZero();
    }
}
