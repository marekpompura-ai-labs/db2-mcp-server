package io.github.marekpompura.db2mcp.policy.sql;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * SqlFingerprint had no dedicated test file before Phase 5's coverage gate
 * (ADR 0014) surfaced it - its three defensive "never throws" branches
 * (null input, unparseable input, any other parse-time exception) were
 * exercised only incidentally, if at all, through other components' tests.
 */
class SqlFingerprintTest {

    private final SqlFingerprint fingerprint = new SqlFingerprint();

    @Test
    void of_producesStatementTypeAndSortedTableList() {
        assertThat(fingerprint.of("SELECT * FROM ITSM.TICKET"))
                .isEqualTo("PLAINSELECT[ITSM.TICKET]");
    }

    @Test
    void of_sortsMultipleTablesAndUppercasesThem() {
        assertThat(fingerprint.of("select t.x from itsm.ticket t, itsm.customer c"))
                .isEqualTo("PLAINSELECT[ITSM.CUSTOMER,ITSM.TICKET]");
    }

    @Test
    void of_stripsQuotesFromTableNames() {
        assertThat(fingerprint.of("SELECT * FROM \"ITSM\".\"TICKET\""))
                .isEqualTo("PLAINSELECT[ITSM.TICKET]");
    }

    @Test
    void of_returnsUnparseableForNullInput() {
        assertThat(fingerprint.of(null)).isEqualTo("UNPARSEABLE");
    }

    @Test
    void of_returnsUnparseableWhenParseReturnsNull() {
        // CCJSqlParserUtil.parse("") returns null rather than throwing (the same
        // ADR 0007 finding SchemaAllowlistValidatorTest reconfirms independently).
        assertThat(fingerprint.of("")).isEqualTo("UNPARSEABLE");
    }

    @Test
    void of_returnsUnparseableWhenParsingThrows() {
        assertThat(fingerprint.of("SELECT FROM WHERE")).isEqualTo("UNPARSEABLE");
    }
}
