package io.github.marekpompura.db2mcp.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The failure case uses a mocked DataSource that throws immediately, not a
 * real unreachable connection - proving the sanitization/logging logic
 * without waiting out the real 10s Hikari connection-timeout a genuinely
 * unreachable host would incur (verified separately, by hand, against the
 * packaged jar - see ADR 0019).
 */
class Db2ConnectivityCheckerTest {

    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Level originalLevel;

    /**
     * Sets the logger to DEBUG explicitly (saved/restored below) rather than
     * relying on {@code logback-spring.xml}'s DEBUG override already being
     * in effect - that override is only applied once some Spring Boot
     * {@code ApplicationContext} has started, which depends on some *other*
     * test class happening to run first in the same Surefire fork. {@link
     * #neverPropagatesARuntimeExceptionFromThePool} asserts on a DEBUG-level
     * line; without this, that assertion is execution-order-dependent and
     * fails deterministically when this class runs in isolation (GitHub
     * issue #32).
     */
    private ListAppender<ILoggingEvent> attachAppender() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Db2ConnectivityChecker.class);
        this.originalLevel = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
        listAppender.start();
        logger.addAppender(listAppender);
        this.appender = listAppender;
        return listAppender;
    }

    @AfterEach
    void detachAppender() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Db2ConnectivityChecker.class);
        if (appender != null) {
            logger.detachAppender(appender);
        }
        logger.setLevel(originalLevel);
    }

    @Test
    void logsOkForARealValidConnection() {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setUrl("jdbc:h2:mem:connectivity_ok;MODE=DB2;DB_CLOSE_DELAY=-1");
        Db2ConnectivityChecker checker = new Db2ConnectivityChecker(h2);
        ListAppender<ILoggingEvent> events = attachAppender();

        checker.checkConnectivity();

        assertThat(events.list).hasSize(1);
        assertThat(events.list.get(0).getFormattedMessage()).isEqualTo("Db2 connectivity check: OK");
    }

    @Test
    void logsSanitizedFailureWithSqlStateWhenConnectionThrows() throws SQLException {
        DataSource failing = mock(DataSource.class);
        SQLException driverError = new SQLException("Connection refused: connect to db2host:50000 as user 'secret_admin'", "08001");
        when(failing.getConnection()).thenThrow(driverError);
        Db2ConnectivityChecker checker = new Db2ConnectivityChecker(failing);
        ListAppender<ILoggingEvent> events = attachAppender();

        checker.checkConnectivity();

        assertThat(events.list).hasSize(1);
        String message = events.list.get(0).getFormattedMessage();
        assertThat(message).contains("FAILED").contains("SQLSTATE=08001");
        // The whole point: the raw driver message (host, username) must never appear.
        assertThat(message).doesNotContain("db2host").doesNotContain("secret_admin");
    }

    @Test
    void logsFailureWithoutSqlStateWhenNoneIsAvailable() throws SQLException {
        DataSource failing = mock(DataSource.class);
        when(failing.getConnection()).thenThrow(new SQLException("generic failure"));
        Db2ConnectivityChecker checker = new Db2ConnectivityChecker(failing);
        ListAppender<ILoggingEvent> events = attachAppender();

        checker.checkConnectivity();

        assertThat(events.list.get(0).getFormattedMessage())
                .contains("FAILED")
                .contains("no SQLSTATE available");
    }

    @Test
    void neverPropagatesARuntimeExceptionFromThePool() throws SQLException {
        // Real bug found by testing this against the packaged jar (ADR 0019):
        // a Hikari driver/URL configuration mismatch throws a plain
        // RuntimeException, not SQLException, before ever reaching JDBC - the
        // first version of this class only caught SQLException, so this
        // exception escaped the ApplicationReadyEvent listener and crashed
        // the entire application startup. This test proves it no longer does.
        DataSource failing = mock(DataSource.class);
        when(failing.getConnection())
                .thenThrow(new RuntimeException("Driver com.ibm.db2.jcc.DB2Driver claims to not accept jdbcUrl, ..."));
        Db2ConnectivityChecker checker = new Db2ConnectivityChecker(failing);
        ListAppender<ILoggingEvent> events = attachAppender();

        org.assertj.core.api.Assertions.assertThatCode(checker::checkConnectivity).doesNotThrowAnyException();

        // Two lines, not one: the WARN above plus a DEBUG line carrying the
        // actual exception (io.github.marekpompura.db2mcp is configured for
        // DEBUG in logback-spring.xml) - the WARN message's own "see DEBUG
        // log for detail" is a real, intended second line, not noise. A
        // prior version of this assertion (hasSize(1)) only ever passed by
        // accident, before any Spring Boot context in the same test JVM had
        // initialized Logback with that DEBUG override applied.
        assertThat(events.list).hasSize(2);
        assertThat(events.list.get(0).getFormattedMessage()).contains("FAILED");
        assertThat(events.list.get(1).getFormattedMessage()).contains("Db2 connectivity check failure detail");
    }

    @Test
    void logsWarningWhenConnectionIsNotValid() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(5)).thenReturn(false);
        Db2ConnectivityChecker checker = new Db2ConnectivityChecker(dataSource);
        ListAppender<ILoggingEvent> events = attachAppender();

        checker.checkConnectivity();

        assertThat(events.list.get(0).getFormattedMessage()).contains("FAILED").contains("reported invalid");
    }
}
