package io.github.marekpompura.db2mcp.configuration;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The readiness half of master brief section 9.4 (ADR 0019): "readiness may
 * validate configuration and a bounded database connection check." Startup
 * validation (ADR 0015) already covers the configuration half; this covers
 * the connection half - once, at startup, logged rather than exposed
 * through an HTTP endpoint (this server has none - ADR 0001), which section
 * 9.4 explicitly allows for stdio mode ("logs/metrics export ... alternative
 * to HTTP-only operational endpoints").
 *
 * <p>Deliberately advisory, not a startup gate: application <em>liveness</em>
 * must not require a live database query (also section 9.4) - a transient
 * Db2 outage at boot shouldn't prevent the process from running or accepting
 * MCP protocol messages, only get logged so an operator can see it. Bounded
 * by the {@code DataSource}'s own {@code connection-timeout} (10s,
 * {@code application.yml}) - reused rather than adding a second timeout
 * property for the same concern.
 */
@Component
public class Db2ConnectivityChecker {

    private static final Logger log = LoggerFactory.getLogger(Db2ConnectivityChecker.class);
    private static final int VALIDATION_TIMEOUT_SECONDS = 5;

    private final DataSource dataSource;

    public Db2ConnectivityChecker(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void checkConnectivity() {
        try (Connection connection = dataSource.getConnection()) {
            if (connection.isValid(VALIDATION_TIMEOUT_SECONDS)) {
                log.info("Db2 connectivity check: OK");
            } else {
                log.warn("Db2 connectivity check: FAILED (connection reported invalid)");
            }
        } catch (SQLException e) {
            // Never the raw driver message - same sanitization rule as
            // Db2QueryAdapter's error handling (ADR 0007, ADR 0018): SQLSTATE
            // only, no hostnames or credentials that might appear in a driver
            // exception message.
            log.warn("Db2 connectivity check: FAILED ({})", sanitize(e));
        } catch (RuntimeException e) {
            // A real bug this class had until it was tested for real (ADR 0019):
            // a connection-pool configuration problem (e.g. a driver/URL
            // mismatch) can throw a plain RuntimeException before ever reaching
            // JDBC's checked SQLException - catching only SQLException let that
            // escape this listener and crash the entire application startup,
            // directly contradicting "never a startup gate" above. Caught here
            // too, message intentionally not included (an unclassified
            // RuntimeException's message isn't guaranteed sanitized the way a
            // JDBC SQLException's SQLSTATE is).
            log.warn("Db2 connectivity check: FAILED (configuration or pool error, see DEBUG log for detail)");
            log.debug("Db2 connectivity check failure detail", e);
        }
    }

    private static String sanitize(SQLException e) {
        return e.getSQLState() != null ? "SQLSTATE=" + e.getSQLState() : "no SQLSTATE available";
    }
}
