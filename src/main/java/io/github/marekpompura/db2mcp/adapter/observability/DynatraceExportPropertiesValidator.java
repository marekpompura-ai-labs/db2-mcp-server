package io.github.marekpompura.db2mcp.adapter.observability;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Fails startup fast (ADR 0015's pattern, applied to Dynatrace export
 * config, thread 5/ADR 0036) if {@code db2mcp.dynatrace.enabled=true} but
 * the environment URL or API token needed to actually export anywhere are
 * missing or malformed - the same reasoning {@link
 * AuditFileAvailabilityValidator} already applies to a broken audit
 * destination: a misconfigured, immediately-fixable operator error should
 * stop the server from starting, not silently produce an exporter that
 * can never successfully send anything.
 */
@Component
public class DynatraceExportPropertiesValidator implements ApplicationRunner {

    private final DynatraceExportProperties properties;

    public DynatraceExportPropertiesValidator(DynatraceExportProperties properties) {
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.enabled()) {
            return;
        }
        List<String> missing = new ArrayList<>();
        if (properties.environmentUrl().isBlank()) {
            missing.add("db2mcp.dynatrace.environment-url");
        }
        if (properties.apiToken().isBlank()) {
            missing.add("db2mcp.dynatrace.api-token");
        }
        if (!missing.isEmpty()) {
            // Load-bearing line for this whole feature's safety: only PROPERTY
            // NAMES are interpolated into this message, never
            // properties.apiToken() itself - see
            // DynatraceExportPropertiesValidatorTest.run_exceptionMessage_neverContainsTheApiTokenValue,
            // which exists specifically to catch a future edit that adds the
            // raw token value here.
            throw new DynatraceConfigurationException("Property: db2mcp.dynatrace.enabled / Value: true / "
                    + "Reason: the following required properties are blank: " + missing
                    + " - Dynatrace export cannot start with enabled=true and missing endpoint/token; "
                    + "either set them or set db2mcp.dynatrace.enabled=false.");
        }
        if (!isHttpsUrl(properties.environmentUrl())) {
            throw new DynatraceConfigurationException(
                    "Property: db2mcp.dynatrace.environment-url / Value: \"" + properties.environmentUrl() + "\" / "
                            + "Reason: must be an absolute https:// URL with no path "
                            + "(e.g. https://abc12345.live.dynatrace.com)");
        }
    }

    private static boolean isHttpsUrl(String url) {
        try {
            URI uri = URI.create(url);
            return "https".equals(uri.getScheme()) && uri.getHost() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
