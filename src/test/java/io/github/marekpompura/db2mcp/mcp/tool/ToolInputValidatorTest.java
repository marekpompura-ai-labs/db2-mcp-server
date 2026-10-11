package io.github.marekpompura.db2mcp.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.marekpompura.db2mcp.domain.error.ErrorCategory;
import io.github.marekpompura.db2mcp.domain.error.McpToolException;
import io.github.marekpompura.db2mcp.mcp.tool.request.ExecuteQueryRequest;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

/**
 * #39 acceptance evidence: the shared collaborator every {@code @Tool}
 * method now runs its request record through before delegating to a port.
 */
class ToolInputValidatorTest {

    private final ToolInputValidator validator =
            new ToolInputValidator(Validation.buildDefaultValidatorFactory().getValidator());

    @Test
    void validate_returnsSilently_whenRequestSatisfiesItsConstraints() {
        validator.validate(new ExecuteQueryRequest("SELECT 1 FROM SYSIBM.SYSDUMMY1"));
    }

    @Test
    void validate_throwsValidationFailed_whenAConstraintIsViolated() {
        assertThatThrownBy(() -> validator.validate(new ExecuteQueryRequest("   ")))
                .isInstanceOf(McpToolException.class)
                .satisfies(e -> assertThat(((McpToolException) e).category()).isEqualTo(ErrorCategory.VALIDATION_FAILED))
                .satisfies(e -> assertThat(((McpToolException) e).correlationId()).isNotBlank());
    }

    /**
     * P0-3/#36's own leak this project already fixed once, for a different
     * validator: a raw {@code jakarta.validation.ConstraintViolationException}
     * message interpolates the failing field's name and constraint default
     * message (e.g. "sql: must not be blank") - never forward that text.
     */
    @Test
    void validate_exceptionMessage_neverContainsTheRawConstraintViolationText() {
        assertThatThrownBy(() -> validator.validate(new ExecuteQueryRequest(null)))
                .isInstanceOf(McpToolException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("must not be blank"))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("ConstraintViolation"));
    }
}
