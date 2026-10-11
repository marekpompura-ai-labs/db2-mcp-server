package io.github.marekpompura.db2mcp.mcp.tool;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.marekpompura.db2mcp.application.port.out.MetadataPort;
import io.github.marekpompura.db2mcp.domain.error.McpToolException;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

/**
 * #39 acceptance evidence: {@code lookupRefData} now rejects a blank/null
 * schema/table/codeColumn/descriptionColumn before ever reaching {@link
 * MetadataPort}. {@code filterColumn}/{@code filterValue} stay unvalidated
 * (both optional, a null pair means "no filter" - see {@code
 * mcp.tool.request.LookupRefDataRequest}'s Javadoc).
 */
class ReferenceDataToolsTest {

    private final MetadataPort metadataPort = mock(MetadataPort.class);
    private final ReferenceDataTools referenceDataTools = new ReferenceDataTools(
            metadataPort, new ToolInputValidator(Validation.buildDefaultValidatorFactory().getValidator()));

    @Test
    void lookupRefData_blankSchema_rejectedBeforeReachingThePort() {
        assertThatThrownBy(() -> referenceDataTools.lookupRefData("  ", "T", "CODE", "DESC", null, null))
                .isInstanceOf(McpToolException.class);

        verifyPortNeverCalled();
    }

    @Test
    void lookupRefData_blankCodeColumn_rejectedBeforeReachingThePort() {
        assertThatThrownBy(() -> referenceDataTools.lookupRefData("S", "T", "  ", "DESC", null, null))
                .isInstanceOf(McpToolException.class);

        verifyPortNeverCalled();
    }

    @Test
    void lookupRefData_nullFilterColumnAndValue_isAValidNoFilterCall() {
        referenceDataTools.lookupRefData("S", "T", "CODE", "DESC", null, null);

        verify(metadataPort).lookupRefData("S", "T", "CODE", "DESC", null, null);
    }

    @Test
    void lookupRefData_allRequiredFieldsPresent_delegatesToThePort() {
        referenceDataTools.lookupRefData("S", "T", "CODE", "DESC", "CATEGORY", "ACTIVE");

        verify(metadataPort).lookupRefData("S", "T", "CODE", "DESC", "CATEGORY", "ACTIVE");
    }

    private void verifyPortNeverCalled() {
        verify(metadataPort, never())
                .lookupRefData(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }
}
