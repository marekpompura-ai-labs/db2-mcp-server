package io.github.marekpompura.db2mcp.adapter.db2;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Tier 2 -> Tier 1 promotion path (ADR 0009, CR-006, 2026-08-14 code
 * review, ADR 0024): the one test in this suite that actually runs
 * against a real DB2 instance when one is reachable, instead of asserting
 * SYSCAT semantics from documentation alone. Self-skips (not fails)
 * whenever {@code DB2_HOST} isn't set - true in CI and in this project's
 * own development environment, the same honest gap ADR 0009 already
 * names for everything catalog-related.
 *
 * <p>Deliberately schema-agnostic: proves genuine connectivity and basic
 * catalog readability against whatever DB2 instance {@code DB2_HOST}
 * points at, without assuming the ITSM demo schema
 * ({@code docs/demo/itsm-schema.sql}) has been loaded there. ADR 0022's
 * {@code SPECIFICNAME}/{@code REFKEYNAME} correctness claims need the
 * ITSM schema plus a deliberately overloaded procedure and a
 * unique-key-referencing foreign key - separate, still-open live-DB2
 * validation work, not conflated with this connectivity check.
 */
@EnabledIfEnvironmentVariable(named = "DB2_HOST", matches = ".+")
class Db2LiveConnectivityIT {

    @Test
    void connectsToRealDb2AndReadsTheCatalog() throws Exception {
        String url = "jdbc:db2://%s:%s/%s"
                .formatted(System.getenv("DB2_HOST"), System.getenv("DB2_PORT"), System.getenv("DB2_DATABASE"));

        try (Connection connection =
                DriverManager.getConnection(url, System.getenv("DB2_USER"), System.getenv("DB2_PASSWORD"))) {
            assertThat(connection.isValid(10)).isTrue();

            try (Statement statement = connection.createStatement();
                    ResultSet rs = statement.executeQuery("SELECT 1 FROM SYSIBM.SYSDUMMY1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }

            try (Statement statement = connection.createStatement();
                    ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM SYSCAT.SCHEMATA")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isGreaterThan(0);
            }
        }
    }
}
