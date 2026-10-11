package io.github.marekpompura.db2mcp.adapter.db2;

import io.github.marekpompura.db2mcp.domain.model.DatabaseProduct;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Which database adapter is active, made explicit rather than inferred
 * (database-portability extension section 11, ADR 0028) - "automatic
 * selection based solely on JDBC URL should not be the primary mechanism
 * because it makes configuration and security intent less explicit."
 *
 * <p>{@link DatabaseProduct} currently has exactly one value ({@code DB2}),
 * so this is also the fail-fast mechanism the extension requires ("startup
 * must fail ... if the selected product has no implementation"): any other
 * configured value simply cannot bind to the enum, and Spring Boot's own
 * binder fails startup with the exact property/value/reason, the same
 * mechanism ADR 0015 already relies on for {@code
 * CapabilityProperties.capabilityProfile} - no hand-written validation
 * needed. "No adapter selected" is handled by defaulting to {@code DB2}
 * (an explicit, documented default, not silent inference) rather than
 * requiring the property to always be set.
 *
 * <p>"More than one adapter active" isn't separately checked here: exactly
 * one {@code CapabilityPort}/{@code MetadataPort}/{@code QueryPort}
 * implementation exists in this codebase today, so Spring's own
 * single-candidate constructor injection already makes that structurally
 * impossible - verified by inspection, not by a redundant runtime check
 * for a condition the current codebase cannot produce.
 */
@Validated
@ConfigurationProperties(prefix = "db2mcp.database")
public record DatabaseSelectionProperties(@DefaultValue("DB2") @NotNull DatabaseProduct product) {
}
