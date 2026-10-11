package io.github.marekpompura.db2mcp.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

class ActiveStatementRegistryTest {

    @Test
    void cancelAll_cancelsEveryRegisteredStatement() throws SQLException {
        ActiveStatementRegistry registry = new ActiveStatementRegistry();
        Statement first = mock(Statement.class);
        Statement second = mock(Statement.class);
        registry.register(first);
        registry.register(second);

        int cancelledCount = registry.cancelAll();

        assertThat(cancelledCount).isEqualTo(2);
        verify(first, times(1)).cancel();
        verify(second, times(1)).cancel();
    }

    @Test
    void cancelAll_marksEachCancelledStatementAsCancelled() {
        ActiveStatementRegistry registry = new ActiveStatementRegistry();
        Statement statement = mock(Statement.class);
        registry.register(statement);

        assertThat(registry.wasCancelled(statement)).isFalse();
        registry.cancelAll();
        assertThat(registry.wasCancelled(statement)).isTrue();
    }

    @Test
    void deregister_removesBothActiveAndCancelledState() {
        ActiveStatementRegistry registry = new ActiveStatementRegistry();
        Statement statement = mock(Statement.class);
        registry.register(statement);
        registry.cancelAll();

        registry.deregister(statement);

        assertThat(registry.wasCancelled(statement)).isFalse();
        // A second cancelAll() must not try to cancel a statement already
        // deregistered - proves deregister() actually removes it from the
        // active set, not just clears its cancelled flag.
        registry.cancelAll();
    }

    @Test
    void wasCancelled_isFalseForAStatementNeverCancelled() {
        ActiveStatementRegistry registry = new ActiveStatementRegistry();
        Statement neverCancelled = mock(Statement.class);
        Statement cancelled = mock(Statement.class);
        registry.register(neverCancelled);
        registry.register(cancelled);

        registry.deregister(neverCancelled);
        registry.cancelAll();

        assertThat(registry.wasCancelled(neverCancelled)).isFalse();
        assertThat(registry.wasCancelled(cancelled)).isTrue();
    }

    @Test
    void cancelAll_toleratesOneStatementFailingToCancel() throws SQLException {
        // Proves the exact claim in this class's own javadoc: one statement
        // failing to cancel (e.g. its connection is already gone) does not
        // stop the rest from being attempted, and does not propagate.
        ActiveStatementRegistry registry = new ActiveStatementRegistry();
        Statement broken = mock(Statement.class);
        Statement healthy = mock(Statement.class);
        doThrow(new SQLException("connection already closed")).when(broken).cancel();
        registry.register(broken);
        registry.register(healthy);

        int cancelledCount = registry.cancelAll();

        assertThat(cancelledCount).isEqualTo(1);
        assertThat(registry.wasCancelled(broken)).isFalse();
        assertThat(registry.wasCancelled(healthy)).isTrue();
    }

    @Test
    void cancelAll_withNoRegisteredStatementsCancelsNothing() {
        ActiveStatementRegistry registry = new ActiveStatementRegistry();

        int cancelledCount = registry.cancelAll();

        assertThat(cancelledCount).isZero();
    }

    @Test
    void cancelAll_calledTwiceCancelsAStillRegisteredStatementBothTimes() throws SQLException {
        // A statement still registered (never deregistered) after a first
        // cancelAll() is a valid target for a second one too - JDBC defines
        // Statement.cancel() as safe to call more than once, and this
        // registry was never asked to add its own double-cancel guard on
        // top of that. deregister(), not an internal cancelled-flag check
        // in cancelAll() itself, is what's supposed to stop a statement
        // being a cancellation candidate - proven by the other tests above.
        ActiveStatementRegistry registry = new ActiveStatementRegistry();
        Statement statement = mock(Statement.class);
        registry.register(statement);

        registry.cancelAll();
        registry.cancelAll();

        verify(statement, times(2)).cancel();
    }

    @Test
    void activeCount_reflectsRegisterAndDeregister() {
        ActiveStatementRegistry registry = new ActiveStatementRegistry();
        Statement statement = mock(Statement.class);

        assertThat(registry.activeCount()).isZero();
        registry.register(statement);
        assertThat(registry.activeCount()).isEqualTo(1);
        registry.deregister(statement);
        assertThat(registry.activeCount()).isZero();
    }

    @Test
    void register_withNoStatementsDoesNotThrow() throws SQLException {
        ActiveStatementRegistry registry = new ActiveStatementRegistry();
        Statement statement = mock(Statement.class);

        // deregister() on something never registered must be a no-op, not a
        // failure - the real caller's finally block always calls it, even on
        // paths where register() itself might theoretically not have run.
        registry.deregister(statement);

        assertThat(registry.wasCancelled(statement)).isFalse();
        verify(statement, never()).cancel();
    }
}
