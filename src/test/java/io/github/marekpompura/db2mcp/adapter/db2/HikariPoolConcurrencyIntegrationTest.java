package io.github.marekpompura.db2mcp.adapter.db2;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.marekpompura.db2mcp.adapter.observability.AuditLogger;
import io.github.marekpompura.db2mcp.adapter.observability.MetricsRecorder;
import io.github.marekpompura.db2mcp.adapter.observability.SpanRecorder;
import io.github.marekpompura.db2mcp.domain.error.McpToolException;
import io.github.marekpompura.db2mcp.policy.capability.CapabilityProfile;
import io.github.marekpompura.db2mcp.policy.capability.CapabilityProperties;
import io.github.marekpompura.db2mcp.policy.sql.AdvancedQueryComplexityLimitsProperties;
import io.github.marekpompura.db2mcp.policy.sql.AdvancedQueryLimitsProperties;
import io.github.marekpompura.db2mcp.policy.sql.QueryComplexityLimitsProperties;
import io.github.marekpompura.db2mcp.policy.sql.QueryLimitsProperties;
import io.github.marekpompura.db2mcp.policy.sql.QueryPolicyProperties;
import io.github.marekpompura.db2mcp.policy.sql.SchemaAllowlistValidator;
import io.github.marekpompura.db2mcp.policy.sql.SqlComplexityValidator;
import io.github.marekpompura.db2mcp.policy.sql.SqlFingerprint;
import io.github.marekpompura.db2mcp.policy.sql.SqlStructuralValidator;
import io.github.marekpompura.db2mcp.testsupport.ItsmH2Database;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.test.simple.SimpleTracer;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Phase 5 thread 4 (ADR 0035): proves what actually happens to a caller when
 * concurrent {@code executeQuery} calls exceed Hikari's {@code
 * maximum-pool-size} - queuing bounded by {@code connection-timeout}, or a
 * sanitized failure - rather than leaving that behavior asserted only by
 * the {@code application.yml} config value with nothing exercising it under
 * real contention. Deliberately smaller pool sizes/timeouts than {@code
 * application.yml}'s real {@code 5}/{@code 10000} are used here purely for
 * test speed and determinism, not as a production-tuning claim - same
 * honesty framing as {@link Db2QueryAdapterIntegrationTest}'s own
 * 1-second-timeout test.
 */
class HikariPoolConcurrencyIntegrationTest {

    // A 2-way self-join of the 1001-row BULK_ROWS table (~10^6 row pairs) -
    // slow enough in H2 to force genuine thread overlap across a small pool,
    // fast enough to keep this test in the Surefire tier rather than needing
    // a live-DB2-scale dataset.
    private static final String SLOW_QUERY = "SELECT COUNT(*) FROM ITSM.BULK_ROWS a JOIN ITSM.BULK_ROWS b ON a.N > 0";

    // Used only by the pool-exhaustion test below, where the connection is
    // never acquired and so the statement is never executed. Trivial on
    // purpose: its cost must not matter to that test's outcome, and a slow
    // query there would imply that it did.
    private static final String TRIVIAL_QUERY = "SELECT COUNT(*) FROM ITSM.BULK_ROWS";

    private static HikariDataSource pooledDataSource(String name, int maxPoolSize, long connectionTimeoutMillis) {
        HikariConfig config = new HikariConfig();
        config.setDataSource(ItsmH2Database.create(name));
        config.setMaximumPoolSize(maxPoolSize);
        config.setConnectionTimeout(connectionTimeoutMillis);
        return new HikariDataSource(config);
    }

    private static Db2QueryAdapter newAdapter(HikariDataSource dataSource) {
        SqlComplexityValidator permissiveComplexityValidator = new SqlComplexityValidator(
                new CapabilityProperties(CapabilityProfile.SAFE_QUERY),
                new QueryComplexityLimitsProperties(1000, 1000),
                new AdvancedQueryComplexityLimitsProperties(1000, 1000));
        return new Db2QueryAdapter(
                dataSource,
                new CapabilityProperties(CapabilityProfile.SAFE_QUERY),
                new SqlStructuralValidator(),
                new SchemaAllowlistValidator(new QueryPolicyProperties(List.of("ITSM"))),
                permissiveComplexityValidator,
                new QueryLimitsProperties(1000, 30, 16384, 1048576),
                new AdvancedQueryLimitsProperties(5000, 120, 65536, 4194304),
                new SqlFingerprint(),
                new AuditLogger(),
                new MetricsRecorder(new SimpleMeterRegistry()),
                new SpanRecorder(new SimpleTracer()),
                new Db2SqlStateClassifier(),
                new io.github.marekpompura.db2mcp.configuration.ActiveStatementRegistry());
    }

    @Test
    void concurrentCallsBeyondPoolSizeQueueAndEventuallySucceed() throws Exception {
        // 4 callers against a 2-connection pool - two rounds of queuing, not
        // ten, so the worst-case drain time (empirically ~2.4-4.2s per query
        // in this environment) stays comfortably inside connectionTimeout.
        HikariDataSource dataSource = pooledDataSource("hikari_pool_queue", 2, 15000);
        try {
            Db2QueryAdapter adapter = newAdapter(dataSource);
            ExecutorService executor = Executors.newFixedThreadPool(4);
            try {
                List<Callable<Object>> tasks =
                        IntStream.range(0, 4).<Callable<Object>>mapToObj(i -> () -> adapter.executeQuery(SLOW_QUERY))
                                .toList();

                List<Future<Object>> futures = executor.invokeAll(tasks, 30, TimeUnit.SECONDS);

                // More callers than the pool has connections - if this weren't
                // queuing (bounded by connectionTimeout) but instead failing
                // outright, at least one of these would throw. All 4
                // succeeding proves the queue-then-serve path, not a race
                // that happened to avoid contention.
                for (Future<Object> future : futures) {
                    assertThat(future.get()).isNotNull();
                }
            } finally {
                executor.shutdownNow();
            }
        } finally {
            dataSource.close();
        }
    }

    @Test
    void concurrentCallsBeyondPoolSizeFailSanitized_whenTimeoutBudgetExceeded() throws Exception {
        // A pool of exactly one connection, which this test then HOLDS for the
        // whole duration of the concurrent calls below. That is what makes the
        // test deterministic, and it is a deliberate change from the earlier
        // version.
        //
        // The earlier version started five concurrent callers running a
        // deliberately slow self-join and asserted that at least one of them
        // failed to acquire a connection inside the 300ms budget. That made
        // the assertion depend on the query being SLOWER than the connection
        // timeout - a performance assumption, not a controlled condition.
        // Measured on one commit: 1.131s in isolation (passed) versus 0.378s
        // inside the full suite (failed), because ~400 preceding tests leave
        // the JVM warm and H2 JIT-compiled. The single connection was then
        // released before any caller's budget expired, so nothing failed and
        // the assertion found zero failures.
        //
        // Holding the connection removes the timing dependency entirely: the
        // pool is exhausted by construction, so no caller can acquire one
        // however fast or slow anything runs.
        HikariDataSource dataSource = pooledDataSource("hikari_pool_exhaustion", 1, 300);
        try (Connection heldConnection = dataSource.getConnection()) {
            assertThat(heldConnection.isClosed()).isFalse();

            Db2QueryAdapter adapter = newAdapter(dataSource);
            ExecutorService executor = Executors.newFixedThreadPool(4);
            try {
                List<Callable<Object>> tasks = IntStream.range(0, 4)
                        .<Callable<Object>>mapToObj(i -> () -> adapter.executeQuery(TRIVIAL_QUERY))
                        .toList();

                List<Future<Object>> futures = executor.invokeAll(tasks, 20, TimeUnit.SECONDS);

                long failureCount = futures.stream()
                        .filter(future -> {
                            try {
                                future.get();
                                return false;
                            } catch (Exception e) {
                                // Connection-acquisition failure sanitization -
                                // Db2QueryAdapterIntegrationTest already proves
                                // statement-execution failures are sanitized, but
                                // nothing else exercises the connection-
                                // acquisition-failure path specifically.
                                Throwable cause = e.getCause();
                                assertThat(cause).isInstanceOf(McpToolException.class);
                                String message = cause.getMessage();
                                assertThat(message)
                                        .doesNotContainIgnoringCase("hikari")
                                        .doesNotContainIgnoringCase("pool")
                                        .doesNotContain("SQLTransientConnectionException");
                                return true;
                            }
                        })
                        .count();

                // ALL four must fail, not merely one. The previous assertion
                // could only say "greater than zero" because it had no control
                // over how many callers lost the race. With the only
                // connection held, every caller must fail - so the stronger
                // assertion is both available and deterministic, and a caller
                // that somehow succeeded would mean the pool handed out a
                // connection it did not have.
                assertThat(failureCount).isEqualTo(4);
            } finally {
                executor.shutdownNow();
            }
        } finally {
            dataSource.close();
        }
    }
}
