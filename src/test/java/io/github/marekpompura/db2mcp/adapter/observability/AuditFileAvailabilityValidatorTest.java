package io.github.marekpompura.db2mcp.adapter.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies CR-011's fail-fast behavior for real, the same way ADR 0015
 * verified Bean Validation actually fires: by pointing the validator at a
 * real filesystem path that genuinely cannot be opened, not by mocking
 * {@link java.nio.file.Files}.
 */
class AuditFileAvailabilityValidatorTest {

    @TempDir
    Path tempDir;

    @Test
    void succeedsAndCreatesParentDirectoriesWhenTheDestinationIsWritable() {
        Path target = tempDir.resolve("nested/audit.log");
        AuditFileAvailabilityValidator validator =
                new AuditFileAvailabilityValidator(new AuditProperties(target.toString()));

        assertThatCode(() -> validator.run(null)).doesNotThrowAnyException();

        assertThat(Files.exists(target)).isTrue();
    }

    @Test
    void leavesExistingAuditContentIntactOnASecondRun() throws IOException {
        Path target = tempDir.resolve("audit.log");
        Files.writeString(target, "correlationId=already-here\n", StandardCharsets.UTF_8);
        AuditFileAvailabilityValidator validator =
                new AuditFileAvailabilityValidator(new AuditProperties(target.toString()));

        validator.run(null);

        assertThat(Files.readString(target)).isEqualTo("correlationId=already-here\n");
    }

    @Test
    void failsFastWithAClearMessageWhenTheDestinationCannotBeCreated() throws IOException {
        // A regular file occupying where a directory needs to be is a
        // deterministic, permission-independent way to force
        // Files.createDirectories to fail - unlike chmod-based approaches,
        // this doesn't depend on which user the build happens to run as.
        Path blocker = tempDir.resolve("blocker");
        Files.writeString(blocker, "not a directory", StandardCharsets.UTF_8);
        Path unreachableTarget = blocker.resolve("audit.log");
        AuditFileAvailabilityValidator validator =
                new AuditFileAvailabilityValidator(new AuditProperties(unreachableTarget.toString()));

        assertThatThrownBy(() -> validator.run(null))
                .isInstanceOf(AuditFileUnavailableException.class)
                .hasMessageContaining("db2mcp.audit.file")
                .hasMessageContaining(unreachableTarget.toString())
                .hasMessageContaining("not writable");
    }
}
