package io.github.marekpompura.db2mcp.adapter.db2;

import io.github.marekpompura.db2mcp.adapter.observability.AuditLogger;
import io.github.marekpompura.db2mcp.adapter.observability.MetricsRecorder;
import io.github.marekpompura.db2mcp.adapter.observability.SpanRecorder;
import io.github.marekpompura.db2mcp.application.port.out.CapabilityPort;
import io.github.marekpompura.db2mcp.domain.audit.AuditEvent;
import io.github.marekpompura.db2mcp.domain.model.CapabilityManifest;
import io.github.marekpompura.db2mcp.domain.model.DatabaseDescriptor;
import io.github.marekpompura.db2mcp.domain.model.EffectiveCapabilities;
import io.github.marekpompura.db2mcp.domain.model.EffectiveLimits;
import io.github.marekpompura.db2mcp.domain.model.IdentifierRules;
import io.github.marekpompura.db2mcp.policy.capability.CapabilityProfile;
import io.github.marekpompura.db2mcp.policy.capability.CapabilityProperties;
import io.github.marekpompura.db2mcp.policy.sql.AdvancedQueryComplexityLimitsProperties;
import io.github.marekpompura.db2mcp.policy.sql.AdvancedQueryLimitsProperties;
import io.github.marekpompura.db2mcp.policy.sql.QueryComplexityLimitsProperties;
import io.github.marekpompura.db2mcp.policy.sql.QueryLimitsProperties;
import io.github.marekpompura.db2mcp.policy.sql.ResolvedComplexityLimits;
import io.github.marekpompura.db2mcp.policy.sql.ResolvedQueryLimits;
import io.micrometer.tracing.Span;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/**
 * Reports what this server actually supports, right now, under its
 * effective capability profile - the database-portability extension's
 * {@code getCapabilities} tool (section 5.2, ADR 0028). {@code
 * productVersion} is queried live from the JDBC driver each call
 * (Tier 1, ADR 0009 - a real H2 connection returns a real H2 version
 * string in this environment; a real DB2 connection would return DB2's own
 * version), never hardcoded - reporting a fixed "11.5" here would be
 * exactly the kind of unverified claim this project's documentation
 * standard rejects.
 *
 * <p>Deliberately never throws: unlike every other tool in this server,
 * capability discovery has no capability/schema gate and no genuine
 * "execution failed" outcome to represent - if the live version query
 * fails (Db2 unreachable), that's reported as a safe value inside the
 * manifest, not as a thrown {@code McpToolException}, the same "a
 * diagnostic feature must not be the reason a caller can't proceed"
 * reasoning ADR 0015's {@code EffectiveConfigReporter} already uses.
 *
 * <p>{@code database.product} in the reported manifest comes from {@link
 * DatabaseSelectionProperties}, not a hardcoded constant - the same
 * explicitly-configured value {@code db2mcp.database.product} fails
 * startup fast on if set to anything Db2 doesn't implement (section 11).
 */
@Component
public class Db2CapabilityAdapter implements CapabilityPort {

    private static final String TOOL_NAME = "getCapabilities";
    private static final String CONTRACT_VERSION = "1.0";
    private static final String SQL_DIALECT = "DB2_LUW";

    // DB2 LUW: unquoted identifiers are folded to uppercase (already relied on
    // throughout adapter.db2 - see Db2MetadataAdapter's IDENTIFIER pattern and
    // fingerprintOf's uppercasing); double-quoted identifiers preserve case.
    private static final IdentifierRules DB2_IDENTIFIER_RULES = new IdentifierRules('"', true);

    private final DataSource dataSource;
    private final CapabilityProfile capabilityProfile;
    private final DatabaseSelectionProperties databaseSelectionProperties;
    private final int maxRows;
    private final int maxCellBytes;
    private final int maxPayloadBytes;
    private final int maxJoins;
    private final int maxNestingDepth;
    private final AuditLogger auditLogger;
    private final MetricsRecorder metricsRecorder;
    private final SpanRecorder spanRecorder;

    public Db2CapabilityAdapter(
            DataSource dataSource,
            CapabilityProperties capabilityProperties,
            DatabaseSelectionProperties databaseSelectionProperties,
            QueryLimitsProperties queryLimitsProperties,
            AdvancedQueryLimitsProperties advancedQueryLimitsProperties,
            QueryComplexityLimitsProperties queryComplexityLimitsProperties,
            AdvancedQueryComplexityLimitsProperties advancedQueryComplexityLimitsProperties,
            AuditLogger auditLogger,
            MetricsRecorder metricsRecorder,
            SpanRecorder spanRecorder) {
        this.dataSource = dataSource;
        this.capabilityProfile = capabilityProperties.capabilityProfile();
        this.databaseSelectionProperties = databaseSelectionProperties;
        // ADR 0032: report whichever limits are actually enforced for this
        // profile, not always the SAFE_QUERY ones - must stay in lockstep
        // with Db2QueryAdapter's own resolution of the same inputs.
        ResolvedQueryLimits resolvedLimits =
                ResolvedQueryLimits.of(capabilityProfile, queryLimitsProperties, advancedQueryLimitsProperties);
        this.maxRows = resolvedLimits.maxRows();
        this.maxCellBytes = resolvedLimits.maxCellBytes();
        this.maxPayloadBytes = resolvedLimits.maxPayloadBytes();
        // ADR 0034: same lockstep requirement, this time against
        // SqlComplexityValidator's own independent resolution.
        ResolvedComplexityLimits resolvedComplexityLimits = ResolvedComplexityLimits.of(
                capabilityProfile, queryComplexityLimitsProperties, advancedQueryComplexityLimitsProperties);
        this.maxJoins = resolvedComplexityLimits.maxJoins();
        this.maxNestingDepth = resolvedComplexityLimits.maxNestingDepth();
        this.auditLogger = auditLogger;
        this.metricsRecorder = metricsRecorder;
        this.spanRecorder = spanRecorder;
    }

    @Override
    public CapabilityManifest capabilities() {
        String correlationId = UUID.randomUUID().toString();
        long start = System.nanoTime();
        Span rootSpan = spanRecorder.startRoot("mcp.tool " + TOOL_NAME);
        rootSpan.tag("tool", TOOL_NAME);
        // #94 (R2-P1-7, 2026-09-17 re-review): this tool was the one place
        // ADR 0041's "every root span carries correlationId" was not true.
        // It held a correlationId all along and wrote it to the audit event,
        // but never to the span - so a trace could not be tied back to the
        // audit line for getCapabilities alone.
        rootSpan.tag("correlationId", correlationId);
        try {
            CapabilityManifest manifest = spanRecorder.inChildSpan(rootSpan, "db2.execute", () -> buildManifest(correlationId));
            long durationMillis = millisSince(start);
            auditLogger.record(AuditEvent.allowed(correlationId, TOOL_NAME, "CAPABILITIES[]", durationMillis, null, null));
            metricsRecorder.recordCall(TOOL_NAME, "allowed", durationMillis);
            // byteLengthIfMeasurable, not byteLength: this class never
            // throws (see the class javadoc), and a metric is not worth
            // failing a diagnostic call over. The empty case is expressed
            // in the type rather than caught and discarded here.
            PayloadBudget.byteLengthIfMeasurable(manifest)
                    .ifPresent(bytes -> metricsRecorder.recordResultBytes(TOOL_NAME, bytes));
            return manifest;
        } finally {
            rootSpan.end();
        }
    }

    private CapabilityManifest buildManifest(String correlationId) {
        DatabaseDescriptor database = new DatabaseDescriptor(
                databaseSelectionProperties.product(), resolveProductVersion(), SQL_DIALECT, DB2_IDENTIFIER_RULES);
        // The seven metadata tools are always registered, under every profile
        // (McpConfig); only row-data access (executeQuery, lookupRefData) is
        // profile-gated (ADR 0006, ADR 0020) - mirrored here exactly, not
        // independently re-decided.
        boolean queryCapable = capabilityProfile.allowsQueryExecution();
        // explainQuery is narrower still than SAFE_QUERY/ADVANCED_QUERY's
        // shared row-data gate above - ADVANCED_QUERY-only (ADR 0033),
        // mirroring McpConfig's own, separately-narrower registration gate
        // for ExplainTools rather than reusing queryCapable.
        boolean explainCapable = capabilityProfile == CapabilityProfile.ADVANCED_QUERY;
        EffectiveCapabilities capabilities =
                new EffectiveCapabilities(true, true, true, true, true, true, queryCapable, queryCapable, explainCapable);
        EffectiveLimits limits = new EffectiveLimits(maxRows, maxCellBytes, maxPayloadBytes, maxJoins, maxNestingDepth);
        return new CapabilityManifest(CONTRACT_VERSION, database, capabilities, limits, correlationId);
    }

    private String resolveProductVersion() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getDatabaseProductVersion();
        } catch (SQLException e) {
            return "(unavailable - could not connect)";
        } catch (RuntimeException e) {
            // A connection-pool/configuration problem can throw a plain
            // RuntimeException before ever reaching a checked SQLException -
            // the same real gap Db2ConnectivityChecker (ADR 0019) was fixed
            // for after being tested for real, applied here from the start.
            return "(unavailable - could not connect)";
        }
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
