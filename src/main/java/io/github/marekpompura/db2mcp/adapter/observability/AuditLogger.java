package io.github.marekpompura.db2mcp.adapter.observability;

import io.github.marekpompura.db2mcp.domain.audit.AuditEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Writes AuditEvents to a dedicated AUDIT logger (ADR 0010), configured in
 * logback-spring.xml to stay separate from the general application log
 * stream - both still go to stderr (ADR 0001 - stdout is protocol-only),
 * just as visibly distinct streams.
 */
@Component
public class AuditLogger {

    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");

    public void record(AuditEvent event) {
        AUDIT.info(
                "correlationId={} tool={} decision={} sql={} durationMs={} rowCount={} truncated={} sqlState={}",
                event.correlationId(),
                event.toolName(),
                event.decision(),
                event.sqlFingerprint(),
                event.durationMillis(),
                event.rowCount(),
                event.truncated(),
                event.sqlState());
    }
}
