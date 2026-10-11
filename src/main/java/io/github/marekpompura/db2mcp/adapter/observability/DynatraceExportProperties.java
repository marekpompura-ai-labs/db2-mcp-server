package io.github.marekpompura.db2mcp.adapter.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Opt-in Dynatrace export config (thread 5, ADR 0036) - a real tenant and
 * API token, so {@code enabled} defaults to {@code false} and {@code
 * environmentUrl}/{@code apiToken} default to blank, matching how
 * {@code enabled=false} means zero Dynatrace beans are registered at all
 * (see {@link io.github.marekpompura.db2mcp.configuration.DynatraceObservabilityConfig}).
 *
 * <p>{@code environmentUrl} is deliberately a single bare-host value (e.g.
 * {@code https://abc12345.live.dynatrace.com}, no path), not one raw
 * endpoint string per signal - Dynatrace's own metrics-registry code uses
 * its configured URI as the complete, literal ingest URL and warns at
 * runtime about the common mistake of passing only the environment base
 * without the correct API path suffix. Each bean-construction call site
 * appends its own correct, hardcoded suffix instead, removing that
 * footgun entirely.
 *
 * <p>No Bean Validation annotations here for "these are required when
 * enabled" - a conditional requirement across three fields isn't
 * expressible with per-field constraints like {@code @NotBlank}. See
 * {@link DynatraceExportPropertiesValidator} for that check, following
 * this codebase's established {@code AuditFileAvailabilityValidator}
 * pattern for exactly this shape of "fail fast at startup, not via Bean
 * Validation" requirement.
 *
 * <p>{@code apiToken} is read only by {@link DynatraceExportPropertiesValidator}
 * (to check it's non-blank - never logged) and by {@code
 * DynatraceObservabilityConfig}'s bean factory methods (to build the
 * outbound Dynatrace request headers/config) - never by {@link
 * io.github.marekpompura.db2mcp.configuration.EffectiveConfigReporter},
 * which is never even constructed with this record as a dependency. That
 * exclusion-by-construction is deliberate, the same pattern already
 * applied there to the DB2 username/password.
 */
@Validated
@ConfigurationProperties(prefix = "db2mcp.dynatrace")
public record DynatraceExportProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String environmentUrl,
        @DefaultValue("") String apiToken) {
}
