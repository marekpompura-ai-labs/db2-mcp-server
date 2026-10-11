package io.github.marekpompura.db2mcp.adapter.observability;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Fails startup fast (ADR 0015's pattern, applied to CR-011) if the
 * configured audit destination cannot be created or opened for writing.
 *
 * <p>Logback's own {@code RollingFileAppender} does not do this: if it
 * can't open its target file, it silently disables the appender and every
 * {@code AUDIT}-logger call afterward becomes a no-op - the server would
 * start and serve tool calls with a broken audit trail and no visible
 * error. This runs independently of Logback, against the exact same
 * resolved path {@code logback-spring.xml} uses, so a misconfigured or
 * unwritable audit destination stops the server from starting at all
 * instead of losing the audit trail silently.
 */
@Component
public class AuditFileAvailabilityValidator implements ApplicationRunner {

    private final AuditProperties auditProperties;

    public AuditFileAvailabilityValidator(AuditProperties auditProperties) {
        this.auditProperties = auditProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        Path path = Path.of(auditProperties.file()).toAbsolutePath();
        Path parent = path.getParent();
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            // Opening (and immediately closing) in append mode is the same
            // operation Logback's RollingFileAppender performs to acquire the
            // file - a real probe of the same failure mode, not a simulation.
            try (FileChannel channel =
                    FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                // no-op: the open (and implicit close) is the check itself
            }
        } catch (IOException e) {
            throw new AuditFileUnavailableException(
                    "Property: db2mcp.audit.file / Value: \"" + auditProperties.file() + "\" / "
                            + "Resolved: \"" + path + "\" / "
                            + "Reason: audit log destination is not writable (" + e.getMessage() + ")",
                    e);
        }
    }
}
