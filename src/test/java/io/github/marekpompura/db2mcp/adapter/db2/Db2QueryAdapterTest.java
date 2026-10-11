package io.github.marekpompura.db2mcp.adapter.db2;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class Db2QueryAdapterTest {

    // requireQueryExecutionAllowed's tests moved to CapabilityProfileTest
    // (ADR 0032) - it's now a shared CapabilityProfile method, not a
    // Db2QueryAdapter-private static.

    @ParameterizedTest
    @ValueSource(
            strings = {
                "SELECT * FROM ITSM.TICKET",
                "  select * from itsm.ticket",
                "WITH x AS (SELECT 1 FROM SYSIBM.SYSDUMMY1) SELECT * FROM x",
                "\n\tSELECT 1"
            })
    void requireReadOnlyStatement_acceptsSelectAndWith(String sql) {
        assertThatCode(() -> Db2QueryAdapter.requireReadOnlyStatement(sql)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "DELETE FROM ITSM.TICKET",
                "UPDATE ITSM.TICKET SET STATUS = 'CL'",
                "INSERT INTO ITSM.TICKET VALUES (1)",
                "DROP TABLE ITSM.TICKET",
                "SELECT 1; DROP TABLE ITSM.TICKET",
                "-- comment\nDELETE FROM ITSM.TICKET",
                "SELECTX FROM ITSM.TICKET"
            })
    void requireReadOnlyStatement_rejectsAnythingElse(String sql) {
        assertThatThrownBy(() -> Db2QueryAdapter.requireReadOnlyStatement(sql))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @org.junit.jupiter.api.Test
    void requireReadOnlyStatement_rejectsNull() {
        assertThatThrownBy(() -> Db2QueryAdapter.requireReadOnlyStatement(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
