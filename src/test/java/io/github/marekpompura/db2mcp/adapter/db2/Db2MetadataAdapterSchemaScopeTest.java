package io.github.marekpompura.db2mcp.adapter.db2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.marekpompura.db2mcp.adapter.observability.AuditLogger;
import io.github.marekpompura.db2mcp.adapter.observability.MetricsRecorder;
import io.github.marekpompura.db2mcp.adapter.observability.SpanRecorder;
import io.github.marekpompura.db2mcp.domain.error.ErrorCategory;
import io.github.marekpompura.db2mcp.domain.error.McpToolException;
import io.github.marekpompura.db2mcp.domain.model.SchemaInfo;
import io.github.marekpompura.db2mcp.policy.capability.CapabilityProfile;
import io.github.marekpompura.db2mcp.policy.capability.CapabilityProperties;
import io.github.marekpompura.db2mcp.policy.sql.AdvancedQueryLimitsProperties;
import io.github.marekpompura.db2mcp.policy.sql.QueryLimitsProperties;
import io.github.marekpompura.db2mcp.policy.sql.QueryPolicyProperties;
import io.github.marekpompura.db2mcp.policy.sql.SchemaAllowlistValidator;
import io.github.marekpompura.db2mcp.testsupport.ItsmH2Database;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.test.simple.SimpleTracer;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

/**
 * P0-1 acceptance evidence (2026-09-01 master-brief gap review, {@code
 * docs/reviews/2026-09-01-master-brief-gap-review-response.md}): before this
 * fix, six of the seven pure metadata methods accepted any caller-supplied
 * schema with no {@link SchemaAllowlistValidator} check at all, and {@code
 * listSchemas} returned every non-system schema DB2 reported regardless of
 * {@code db2mcp.allowed-schemas}. A client under the default {@code
 * METADATA_ONLY} profile could enumerate and describe catalog objects in
 * any schema. These tests fail against the pre-fix code (confirmed by
 * temporarily reverting {@code Db2MetadataAdapter}'s scope checks and
 * observing every rejection test below start passing with the wrong
 * category, or not throwing at all) and pass against the fix.
 */
class Db2MetadataAdapterSchemaScopeTest {

    private static Db2MetadataAdapter newAdapter(DataSource dataSource, List<String> allowedSchemas) {
        return new Db2MetadataAdapter(
                dataSource,
                new CapabilityProperties(CapabilityProfile.SAFE_QUERY),
                new SchemaAllowlistValidator(new QueryPolicyProperties(allowedSchemas)),
                new QueryLimitsProperties(1000, 30, 16384, 1048576),
                new AdvancedQueryLimitsProperties(5000, 120, 65536, 4194304),
                new AuditLogger(),
                new MetricsRecorder(new SimpleMeterRegistry()),
                new SpanRecorder(new SimpleTracer()),
                new Db2SqlStateClassifier());
    }

    // --- Rejected before any DB2/H2 catalog query runs (SCOPE_DENIED, not EXECUTION_FAILED) ---

    @Test
    void listTables_rejectsSchemaOutsideAllowlist() {
        DataSource dataSource = ItsmH2Database.create("scope_list_tables");
        Db2MetadataAdapter adapter = newAdapter(dataSource, List.of("ITSM"));

        assertThatThrownBy(() -> adapter.listTables("OTHER_SCHEMA"))
                .isInstanceOf(McpToolException.class)
                .extracting(t -> ((McpToolException) t).category())
                .isEqualTo(ErrorCategory.SCOPE_DENIED);
    }

    @Test
    void describeTable_rejectsSchemaOutsideAllowlist() {
        DataSource dataSource = ItsmH2Database.create("scope_describe_table");
        Db2MetadataAdapter adapter = newAdapter(dataSource, List.of("ITSM"));

        assertThatThrownBy(() -> adapter.describeTable("OTHER_SCHEMA", "SECRET"))
                .isInstanceOf(McpToolException.class)
                .extracting(t -> ((McpToolException) t).category())
                .isEqualTo(ErrorCategory.SCOPE_DENIED);
    }

    @Test
    void getTableDependencies_rejectsSchemaOutsideAllowlist() {
        DataSource dataSource = ItsmH2Database.create("scope_table_dependencies");
        Db2MetadataAdapter adapter = newAdapter(dataSource, List.of("ITSM"));

        assertThatThrownBy(() -> adapter.getTableDependencies("OTHER_SCHEMA", "SECRET"))
                .isInstanceOf(McpToolException.class)
                .extracting(t -> ((McpToolException) t).category())
                .isEqualTo(ErrorCategory.SCOPE_DENIED);
    }

    @Test
    void listProcedures_rejectsSchemaOutsideAllowlist() {
        DataSource dataSource = ItsmH2Database.create("scope_list_procedures");
        Db2MetadataAdapter adapter = newAdapter(dataSource, List.of("ITSM"));

        assertThatThrownBy(() -> adapter.listProcedures("OTHER_SCHEMA"))
                .isInstanceOf(McpToolException.class)
                .extracting(t -> ((McpToolException) t).category())
                .isEqualTo(ErrorCategory.SCOPE_DENIED);
    }

    @Test
    void describeProcedure_rejectsSchemaOutsideAllowlist() {
        DataSource dataSource = ItsmH2Database.create("scope_describe_procedure");
        Db2MetadataAdapter adapter = newAdapter(dataSource, List.of("ITSM"));

        assertThatThrownBy(() -> adapter.describeProcedure("OTHER_SCHEMA", "SPECIFIC_1"))
                .isInstanceOf(McpToolException.class)
                .extracting(t -> ((McpToolException) t).category())
                .isEqualTo(ErrorCategory.SCOPE_DENIED);
    }

    @Test
    void getProcedureSource_rejectsSchemaOutsideAllowlist() {
        DataSource dataSource = ItsmH2Database.create("scope_procedure_source");
        Db2MetadataAdapter adapter = newAdapter(dataSource, List.of("ITSM"));

        assertThatThrownBy(() -> adapter.getProcedureSource("OTHER_SCHEMA", "SPECIFIC_1"))
                .isInstanceOf(McpToolException.class)
                .extracting(t -> ((McpToolException) t).category())
                .isEqualTo(ErrorCategory.SCOPE_DENIED);
    }

    @Test
    void listTables_emptyAllowlistRejectsEveryCall() {
        // The same fail-closed default executeQuery/lookupRefData already have:
        // an unconfigured allowlist must not silently expose every schema.
        DataSource dataSource = ItsmH2Database.create("scope_empty_allowlist");
        Db2MetadataAdapter adapter = newAdapter(dataSource, List.of());

        assertThatThrownBy(() -> adapter.listTables("ITSM"))
                .isInstanceOf(McpToolException.class)
                .extracting(t -> ((McpToolException) t).category())
                .isEqualTo(ErrorCategory.SCOPE_DENIED);
    }

    @Test
    void rejectionMessage_neverContainsTheRawSchemaNameOrValidatorInternals() {
        // P0-3, same review: the McpToolException message must be the stable
        // generic string, never SchemaAllowlistValidator's own exception
        // message (which embeds the caller-supplied schema verbatim).
        DataSource dataSource = ItsmH2Database.create("scope_message_sanitized");
        Db2MetadataAdapter adapter = newAdapter(dataSource, List.of("ITSM"));

        assertThatThrownBy(() -> adapter.listTables("SOME_SECRET_INTERNAL_SCHEMA_NAME"))
                .satisfies(t -> assertThat(t.getMessage()).doesNotContain("SOME_SECRET_INTERNAL_SCHEMA_NAME"));
    }

    // --- listSchemas: filtered to the allowlist, not every non-system schema ---

    private static DataSource syntheticSchemaCatalog(String uniqueName) throws SQLException {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setUrl("jdbc:h2:mem:" + uniqueName + ";MODE=DB2;DB_CLOSE_DELAY=-1");
        try (Connection connection = dataSource.getConnection()) {
            connection.createStatement().execute("CREATE SCHEMA SYSCAT");
            connection.createStatement().execute("CREATE TABLE SYSCAT.SCHEMATA (SCHEMANAME VARCHAR(128))");
            connection
                    .createStatement()
                    .execute("INSERT INTO SYSCAT.SCHEMATA VALUES ('ITSM'), ('HR'), ('FINANCE')");
        }
        return dataSource;
    }

    @Test
    void listSchemas_returnsOnlyAllowedSchemasFromAWiderCatalog() throws SQLException {
        DataSource dataSource = syntheticSchemaCatalog("scope_list_schemas_filtered");
        Db2MetadataAdapter adapter = newAdapter(dataSource, List.of("ITSM"));

        List<SchemaInfo> schemas = adapter.listSchemas().items();

        assertThat(schemas).containsExactly(new SchemaInfo("ITSM"));
    }

    @Test
    void listSchemas_emptyAllowlistReturnsNoSchemas() throws SQLException {
        DataSource dataSource = syntheticSchemaCatalog("scope_list_schemas_empty_allowlist");
        Db2MetadataAdapter adapter = newAdapter(dataSource, List.of());

        List<SchemaInfo> schemas = adapter.listSchemas().items();

        assertThat(schemas).isEmpty();
    }

    @Test
    void listSchemas_multipleAllowedSchemasAllSurviveTheFilter() throws SQLException {
        DataSource dataSource = syntheticSchemaCatalog("scope_list_schemas_multiple");
        Db2MetadataAdapter adapter = newAdapter(dataSource, List.of("ITSM", "FINANCE"));

        List<SchemaInfo> schemas = adapter.listSchemas().items();

        assertThat(schemas).containsExactlyInAnyOrder(new SchemaInfo("ITSM"), new SchemaInfo("FINANCE"));
    }
}
