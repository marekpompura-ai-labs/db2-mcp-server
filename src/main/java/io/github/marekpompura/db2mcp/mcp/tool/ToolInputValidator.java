package io.github.marekpompura.db2mcp.mcp.tool;

import io.github.marekpompura.db2mcp.domain.error.ErrorCategory;
import io.github.marekpompura.db2mcp.domain.error.McpToolException;
import jakarta.validation.Validator;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The single place every {@code @Tool} method runs its request record
 * through Bean Validation (P1-2, 2026-09-01 master-brief gap review,
 * #39) - before this class, none of the 11 {@code @Tool} methods
 * validated their input at all beyond what each adapter happened to
 * check deep inside its own call chain.
 *
 * <p>Deliberately not Spring's AOP-based method validation
 * ({@code @Validated} on the tool classes plus constraints directly on
 * {@code @ToolParam} parameters): the tool objects passed to {@code
 * MethodToolCallbackProvider.toolObjects(...)} (see {@code McpConfig})
 * are introspected and invoked via plain reflection, and whether a
 * CGLIB-proxied bean's copied methods still carry the original
 * annotations {@code MethodToolCallbackProvider} looks for is exactly
 * the kind of framework-internals question this project insists on
 * verifying, not assuming (ADR 0040). Explicit,
 * in-method validation of an internally-constructed request record has
 * no such risk and leaves every tool's public signature - and therefore
 * the checked-in MCP contract snapshot - untouched.
 *
 * <p>On failure, throws the same {@link McpToolException} every other
 * rejection path in this server throws (ADR 0018), with a single
 * generic message - never the raw {@code ConstraintViolationException}
 * text, which would echo interpolated constraint messages and property
 * paths back to the client (the same class of leak P0-3/#36 closed for
 * the SQL and schema validators).
 */
@Component
public class ToolInputValidator {

    private final Validator validator;

    public ToolInputValidator(Validator validator) {
        this.validator = validator;
    }

    public <T> void validate(T request) {
        Set<?> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new McpToolException(
                    ErrorCategory.VALIDATION_FAILED,
                    "One or more tool parameters failed validation",
                    null,
                    UUID.randomUUID().toString());
        }
    }
}
