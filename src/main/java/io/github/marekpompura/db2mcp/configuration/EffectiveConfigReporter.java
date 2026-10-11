package io.github.marekpompura.db2mcp.configuration;

import io.github.marekpompura.db2mcp.adapter.db2.DatabaseSelectionProperties;
import io.github.marekpompura.db2mcp.adapter.observability.AuditProperties;
import io.github.marekpompura.db2mcp.policy.capability.CapabilityProfile;
import io.github.marekpompura.db2mcp.policy.capability.CapabilityProperties;
import io.github.marekpompura.db2mcp.policy.sql.AdvancedQueryLimitsProperties;
import io.github.marekpompura.db2mcp.policy.sql.QueryLimitsProperties;
import io.github.marekpompura.db2mcp.policy.sql.QueryPolicyProperties;
import io.github.marekpompura.db2mcp.policy.sql.ResolvedQueryLimits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Logs the effective configuration once at startup (ADR 0015), to stderr
 * like every other log line (ADR 0001 - stdout is JSON-RPC only), so an
 * operator can see what the server actually resolved without reading
 * environment variables by hand.
 *
 * <p>Deliberately narrow about what it reads: this class only ever
 * receives the six {@code db2mcp.*} properties records (none of which
 * can hold a credential - {@link QueryPolicyProperties},
 * {@link QueryLimitsProperties}, {@link AdvancedQueryLimitsProperties},
 * {@link CapabilityProperties}, {@link AuditProperties},
 * {@link DatabaseSelectionProperties}) plus the raw {@code
 * spring.datasource.url} string, read once for its scheme prefix only. It
 * never touches {@code spring.datasource.username} or {@code .password} -
 * not "reads but hides them," never wired to them at all, so there's no
 * field a future edit could accidentally log.
 *
 * <p>P0-3 (2026-09-01 master-brief gap review): this class previously
 * logged the full {@code spring.datasource.url} (redacted only for an
 * embedded {@code user:pass@} userinfo segment - a format this project's
 * own URLs never actually use, so the redaction was a no-op in practice),
 * the complete {@code allowedSchemas} list, and the audit log's full
 * filesystem path - internal hostname, port, database name, schema names,
 * and filesystem topology, none of it a credential, but all of it exactly
 * the kind of implementation/topology detail master brief §7.4/§9 asks not
 * to log by default. Now reports only safe categories/counts: a schema
 * count, whether the audit file is configured, and the datasource URL's
 * scheme prefix (e.g. {@code jdbc:db2}) rather than host/port/database.
 * This is a startup log line, not an MCP-client-visible channel, so it was
 * never part of the client-facing sanitization surface - but the same
 * telemetry-privacy discipline applies to what operators' own log
 * aggregation might otherwise capture.
 *
 * <p>The logged {@code maxRows}/{@code timeoutSeconds}/{@code
 * maxCellBytes}/{@code maxPayloadBytes} are the <em>resolved</em> limits
 * (ADR 0032, {@link ResolvedQueryLimits}) - the {@code ADVANCED_QUERY}
 * profile's own numbers when that profile is active, not always {@link
 * QueryLimitsProperties}'s. Logging the wrong (base) numbers here would
 * defeat this class's own stated purpose: an operator checking "what
 * limits are actually in effect" must see the truth, not the SAFE_QUERY
 * defaults regardless of the active profile.
 */
@Component
public class EffectiveConfigReporter {

    private static final Logger log = LoggerFactory.getLogger(EffectiveConfigReporter.class);

    private final CapabilityProperties capabilityProperties;
    private final QueryPolicyProperties queryPolicyProperties;
    private final QueryLimitsProperties queryLimitsProperties;
    private final AdvancedQueryLimitsProperties advancedQueryLimitsProperties;
    private final AuditProperties auditProperties;
    private final DatabaseSelectionProperties databaseSelectionProperties;
    private final Environment environment;

    public EffectiveConfigReporter(
            CapabilityProperties capabilityProperties,
            QueryPolicyProperties queryPolicyProperties,
            QueryLimitsProperties queryLimitsProperties,
            AdvancedQueryLimitsProperties advancedQueryLimitsProperties,
            AuditProperties auditProperties,
            DatabaseSelectionProperties databaseSelectionProperties,
            Environment environment) {
        this.capabilityProperties = capabilityProperties;
        this.queryPolicyProperties = queryPolicyProperties;
        this.queryLimitsProperties = queryLimitsProperties;
        this.advancedQueryLimitsProperties = advancedQueryLimitsProperties;
        this.auditProperties = auditProperties;
        this.databaseSelectionProperties = databaseSelectionProperties;
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logEffectiveConfiguration() {
        CapabilityProfile capabilityProfile = capabilityProperties.capabilityProfile();
        ResolvedQueryLimits resolvedLimits =
                ResolvedQueryLimits.of(capabilityProfile, queryLimitsProperties, advancedQueryLimitsProperties);
        log.info(
                "Effective configuration: databaseProduct={} capabilityProfile={} allowedSchemaCount={} maxRows={} "
                        + "timeoutSeconds={} maxCellBytes={} maxPayloadBytes={} auditFileConfigured={} "
                        + "datasourceScheme={} hikariMaximumPoolSize={} hikariReadOnly={}",
                databaseSelectionProperties.product(),
                capabilityProfile,
                queryPolicyProperties.allowedSchemas().size(),
                resolvedLimits.maxRows(),
                resolvedLimits.timeoutSeconds(),
                resolvedLimits.maxCellBytes(),
                resolvedLimits.maxPayloadBytes(),
                auditProperties.file() != null && !auditProperties.file().isBlank(),
                datasourceScheme(),
                environment.getProperty("spring.datasource.hikari.maximum-pool-size", "(unset)"),
                environment.getProperty("spring.datasource.hikari.read-only", "(unset)"));
    }

    /**
     * The datasource URL's scheme prefix only (e.g. {@code jdbc:db2}), not
     * host/port/database name - see the class javadoc's P0-3 note. A
     * reporting feature must never be the reason the application fails to
     * start: {@code spring.datasource.url}'s {@code ${DB2_HOST}} etc.
     * placeholders are left unresolved (not defaulted) when those variables
     * aren't set - a real, expected state before secrets are wired up in a
     * deployment - and {@link Environment#getProperty} throws rather than
     * returning a partial string in that case (a stricter behavior than
     * whatever Spring Boot's own DataSource auto-configuration relies on,
     * which is why the application itself starts fine in that same state).
     * Caught here and reported as a value, not allowed to propagate.
     */
    private String datasourceScheme() {
        String url;
        try {
            url = environment.getProperty("spring.datasource.url", "(unset)");
        } catch (RuntimeException e) {
            return "(unresolvable - one or more placeholders not set)";
        }
        int schemeEnd = url.indexOf("://");
        return schemeEnd > 0 ? url.substring(0, schemeEnd) : url;
    }
}
