package io.github.marekpompura.db2mcp.adapter.db2;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.marekpompura.db2mcp.domain.error.ErrorCategory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class Db2SqlStateClassifierTest {

    private final Db2SqlStateClassifier classifier = new Db2SqlStateClassifier();

    @ParameterizedTest
    @CsvSource({
        "57014, QUERY_TIMEOUT", // empirically verified - see class Javadoc
        "42501, DB2_AUTHORIZATION_FAILED",
        "42704, DB2_OBJECT_NOT_FOUND",
        "23505, DB2_EXECUTION_FAILED", // an unclassified but real SQLSTATE - falls through to the default
        "99999, DB2_EXECUTION_FAILED"
    })
    void classifiesKnownAndUnknownSqlStates(String sqlState, ErrorCategory expected) {
        assertThat(classifier.classify(sqlState)).isEqualTo(expected);
    }

    @Test
    void treatsNullSqlStateAsExecutionFailed() {
        assertThat(classifier.classify(null)).isEqualTo(ErrorCategory.DB2_EXECUTION_FAILED);
    }
}
