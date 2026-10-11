package io.github.marekpompura.db2mcp.adapter.db2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.marekpompura.db2mcp.adapter.observability.AuditLogger;
import io.github.marekpompura.db2mcp.adapter.observability.MetricsRecorder;
import io.github.marekpompura.db2mcp.adapter.observability.SpanRecorder;
import io.github.marekpompura.db2mcp.domain.error.ErrorCategory;
import io.github.marekpompura.db2mcp.domain.error.McpToolException;
import io.github.marekpompura.db2mcp.domain.model.ReferenceDataEntry;
import io.github.marekpompura.db2mcp.policy.capability.CapabilityProfile;
import io.github.marekpompura.db2mcp.policy.capability.CapabilityProperties;
import io.github.marekpompura.db2mcp.policy.sql.AdvancedQueryLimitsProperties;
import io.github.marekpompura.db2mcp.policy.sql.QueryLimitsProperties;
import io.github.marekpompura.db2mcp.policy.sql.QueryPolicyProperties;
import io.github.marekpompura.db2mcp.policy.sql.SchemaAllowlistValidator;
import io.github.marekpompura.db2mcp.testsupport.ItsmH2Database;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.test.simple.SimpleTracer;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * Tier 1 integration coverage (ADR 0009): lookupRefData against a real H2
 * connection (MODE=DB2), including the identifier-concatenated dynamic SQL
 * path (ADR 0002 layer 3), not mocks. H2-verified, not Db2-verified.
 *
 * <p>Also the adversarial acceptance evidence CR-001 (2026-08-14 code
 * review) requires: METADATA_ONLY must not expose row-data access through
 * this tool, and a schema outside the allowlist must be rejected the same
 * way it already is for executeQuery.
 */
class Db2MetadataAdapterLookupRefDataIntegrationTest {

    private static Db2MetadataAdapter newAdapter(DataSource dataSource, CapabilityProfile profile, List<String> allowedSchemas) {
        return newAdapter(dataSource, profile, allowedSchemas, new QueryLimitsProperties(1000, 30, 16384, 1048576));
    }

    private static Db2MetadataAdapter newAdapter(
            DataSource dataSource, CapabilityProfile profile, List<String> allowedSchemas, QueryLimitsProperties limits) {
        return new Db2MetadataAdapter(
                dataSource,
                new CapabilityProperties(profile),
                new SchemaAllowlistValidator(new QueryPolicyProperties(allowedSchemas)),
                limits,
                new AdvancedQueryLimitsProperties(5000, 120, 65536, 4194304),
                new AuditLogger(),
                new MetricsRecorder(new SimpleMeterRegistry()),
                new SpanRecorder(new SimpleTracer()),
                new Db2SqlStateClassifier());
    }

    private static Db2MetadataAdapter newAdapter(
            DataSource dataSource,
            CapabilityProfile profile,
            List<String> allowedSchemas,
            QueryLimitsProperties limits,
            MeterRegistry registry,
            Tracer tracer) {
        return new Db2MetadataAdapter(
                dataSource,
                new CapabilityProperties(profile),
                new SchemaAllowlistValidator(new QueryPolicyProperties(allowedSchemas)),
                limits,
                new AdvancedQueryLimitsProperties(5000, 120, 65536, 4194304),
                new AuditLogger(),
                new MetricsRecorder(registry),
                new SpanRecorder(tracer),
                new Db2SqlStateClassifier());
    }

    @Test
    void lookupRefData_runsRealFilteredQueryAgainstH2() {
        DataSource dataSource = ItsmH2Database.create("lookup_refdata");
        Db2MetadataAdapter adapter = newAdapter(dataSource, CapabilityProfile.SAFE_QUERY, List.of("ITSM"));

        var entries = adapter.lookupRefData("ITSM", "REF_DATA", "CODE", "DESCRIPTION", "CATEGORY", "PRIORITY").items();

        assertThat(entries)
                .containsExactly(
                        new ReferenceDataEntry("H", "High"),
                        new ReferenceDataEntry("L", "Low"),
                        new ReferenceDataEntry("N", "Normal"));
    }

    @Test
    void lookupRefData_runsRealUnfilteredQueryAgainstH2() {
        DataSource dataSource = ItsmH2Database.create("lookup_refdata_unfiltered");
        Db2MetadataAdapter adapter = newAdapter(dataSource, CapabilityProfile.SAFE_QUERY, List.of("ITSM"));

        var entries = adapter.lookupRefData("ITSM", "REF_DATA", "CODE", "DESCRIPTION", null, null).items();

        assertThat(entries).hasSize(5); // 3 PRIORITY + 2 STATUS rows seeded in itsm-h2-schema.sql
    }

    @Test
    void lookupRefData_rejectsUnderMetadataOnlyProfile() {
        DataSource dataSource = ItsmH2Database.create("lookup_refdata_metadata_only");
        Db2MetadataAdapter adapter = newAdapter(dataSource, CapabilityProfile.METADATA_ONLY, List.of("ITSM"));

        assertThatThrownBy(() -> adapter.lookupRefData("ITSM", "REF_DATA", "CODE", "DESCRIPTION", null, null))
                .isInstanceOf(McpToolException.class)
                .extracting(t -> ((McpToolException) t).category())
                .isEqualTo(ErrorCategory.CAPABILITY_DISABLED);
    }

    // --- P0-2 (2026-09-01 master-brief gap review): lookupRefData previously had
    // no cell or payload limit at all and could not report truncation. ---

    @Test
    void lookupRefData_boundsEachCellToTheConfiguredByteLimit() {
        DataSource dataSource = ItsmH2Database.create("lookup_refdata_cell_bound");
        // A 3-byte cell budget is smaller than every real DESCRIPTION value
        // in the fixture ("High", "Low", "Normal") - proves the hard-cap
        // invariant end to end through the real adapter wiring (every
        // returned cell's byte length never exceeds the configured limit),
        // not just Db2ValueNormalizer's truncation math in isolation.
        Db2MetadataAdapter adapter = newAdapter(
                dataSource, CapabilityProfile.SAFE_QUERY, List.of("ITSM"), new QueryLimitsProperties(1000, 30, 3, 1048576));

        var entries = adapter.lookupRefData("ITSM", "REF_DATA", "CODE", "DESCRIPTION", "CATEGORY", "PRIORITY").items();

        assertThat(entries).allSatisfy(entry -> assertThat(
                        entry.description().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(3));
    }

    @Test
    void lookupRefData_boundsPayloadBytesAndReportsTruncationThroughTheEnvelope() throws Exception {
        DataSource dataSource = ItsmH2Database.create("lookup_refdata_payload_bound");
        // 5 rows exist unfiltered (3 PRIORITY + 2 STATUS). #62 (2026-09-09
        // re-review, RR-P0-1): a budget too small for all five must cut the
        // response short *and* say so through the returned envelope - the
        // previous bare List<ReferenceDataEntry> left the caller unable to
        // tell a complete result from a truncated one, even though the
        // server had already calculated and audited the difference.
        int budget = 200;
        Db2MetadataAdapter adapter = newAdapter(
                dataSource,
                CapabilityProfile.SAFE_QUERY,
                List.of("ITSM"),
                new QueryLimitsProperties(1000, 30, 16384, budget));

        var result = adapter.lookupRefData("ITSM", "REF_DATA", "CODE", "DESCRIPTION", null, null);

        assertThat(result.items().size()).isLessThan(5);
        assertThat(result.truncated()).isTrue();
        assertThat(result.rowCount()).isEqualTo(result.items().size());
        assertThat(new ObjectMapper().writeValueAsBytes(result).length).isLessThanOrEqualTo(budget);
    }

    @Test
    void lookupRefData_refusesWhenEvenTheEmptyEnvelopeIsOverBudget() {
        DataSource dataSource = ItsmH2Database.create("lookup_refdata_payload_refused");
        // A 20-byte budget cannot fit the envelope's own correlation ID,
        // let alone an entry - refused rather than answered over the cap.
        Db2MetadataAdapter adapter = newAdapter(
                dataSource, CapabilityProfile.SAFE_QUERY, List.of("ITSM"), new QueryLimitsProperties(1000, 30, 16384, 20));

        assertThatThrownBy(() -> adapter.lookupRefData("ITSM", "REF_DATA", "CODE", "DESCRIPTION", null, null))
                .isInstanceOf(McpToolException.class)
                .extracting(e -> ((McpToolException) e).category())
                .isEqualTo(ErrorCategory.RESULT_LIMIT_EXCEEDED);
    }

    // P0-3 (2026-09-01 review): requireIdentifier's own exception embeds the
    // raw caller-supplied identifier verbatim - previously forwarded to the
    // MCP client unchanged.
    @Test
    void lookupRefData_identifierRejectionMessageNeverContainsTheRawIdentifier() {
        DataSource dataSource = ItsmH2Database.create("lookup_refdata_identifier_sanitization");
        Db2MetadataAdapter adapter = newAdapter(dataSource, CapabilityProfile.SAFE_QUERY, List.of("ITSM"));

        assertThatThrownBy(() -> adapter.lookupRefData(
                        "ITSM", "REF_DATA", "CODE; DROP TABLE SENTINEL_LEAK", "DESCRIPTION", null, null))
                .isInstanceOf(McpToolException.class)
                .hasMessageNotContaining("SENTINEL_LEAK");
    }

    @Test
    void lookupRefData_rejectsSchemaOutsideAllowlist() {
        DataSource dataSource = ItsmH2Database.create("lookup_refdata_scope");
        // SAFE_QUERY is active, but ITSM was never added to the allowlist -
        // the exact bypass CR-001 found: capability alone is not enough.
        Db2MetadataAdapter adapter = newAdapter(dataSource, CapabilityProfile.SAFE_QUERY, List.of());

        assertThatThrownBy(() -> adapter.lookupRefData("ITSM", "REF_DATA", "CODE", "DESCRIPTION", null, null))
                .isInstanceOf(McpToolException.class)
                .extracting(t -> ((McpToolException) t).category())
                .isEqualTo(ErrorCategory.SCOPE_DENIED);
    }

    // --- P1-3 (2026-09-01 master-brief gap review, #40, ADR 0041): the
    // review found mcp.result.bytes missing entirely, and every span
    // project-wide tagged only "tool", never a correlationId. ---

    @Test
    void lookupRefData_recordsResultBytesMetricAndTagsTheRootSpanWithCorrelationId() {
        DataSource dataSource = ItsmH2Database.create("lookup_refdata_observability");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SimpleTracer tracer = new SimpleTracer();
        Db2MetadataAdapter adapter = newAdapter(
                dataSource,
                CapabilityProfile.SAFE_QUERY,
                List.of("ITSM"),
                new QueryLimitsProperties(1000, 30, 16384, 1048576),
                registry,
                tracer);

        adapter.lookupRefData("ITSM", "REF_DATA", "CODE", "DESCRIPTION", null, null);

        assertThat(registry.get("mcp.result.bytes").tag("tool", "lookupRefData").summary().count()).isEqualTo(1);
        assertThat(registry.get("mcp.result.bytes").tag("tool", "lookupRefData").summary().totalAmount())
                .isGreaterThan(0.0);
        assertThat(tracer.getSpans()).isNotEmpty();
        // SimpleTracer records in start order (confirmed directly in
        // Db2QueryAdapterIntegrationTest); the root span is first.
        assertThat(tracer.getSpans().getFirst().getTags()).containsKey("correlationId");
    }
}
