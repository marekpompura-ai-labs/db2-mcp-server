package io.github.marekpompura.db2mcp.policy.sql;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.stereotype.Component;

/**
 * Restricts executeQuery to a configured set of schemas (ADR 0008), on top
 * of SqlStructuralValidator's statement-type check. Table references without
 * an explicit schema qualifier are rejected outright, since their resolved
 * schema depends on session state (CURRENT SCHEMA) this validator cannot
 * verify statically.
 */
@Component
public class SchemaAllowlistValidator {

    private final Set<String> allowedSchemas;

    public SchemaAllowlistValidator(QueryPolicyProperties properties) {
        this.allowedSchemas = properties.allowedSchemas().stream()
                .map(s -> s.toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Same allowlist as {@link #validate(String)}, for callers that supply a
     * schema name directly rather than SQL to parse - {@code lookupRefData}
     * (CR-001, master brief extension section 9) builds its own SQL from
     * caller-supplied identifiers, so there is no statement to hand to
     * JSQLParser. Also the schema-scoping check for the seven pure metadata
     * methods (P0-1, 2026-09-01 master-brief gap review): before this was
     * wired into {@code Db2MetadataAdapter}, only {@code lookupRefData} and
     * {@code executeQuery}/{@code explainQuery} enforced this allowlist - a
     * client under the default {@code METADATA_ONLY} profile could enumerate
     * and describe catalog objects in any schema, allowlisted or not.
     */
    public void validateSchema(String schema) {
        if (!isAllowed(schema)) {
            throw new IllegalArgumentException("Schema '" + schema + "' is not an allowed schema");
        }
    }

    /**
     * Non-throwing form of {@link #validateSchema(String)}, for filtering a
     * result set down to allowed schemas (e.g. {@code
     * Db2MetadataAdapter#listSchemas}) rather than rejecting a single
     * caller-supplied schema outright.
     */
    public boolean isAllowed(String schema) {
        return schema != null && allowedSchemas.contains(schema.toUpperCase(Locale.ROOT));
    }

    public void validate(String sql) {
        Statement statement;
        try {
            statement = CCJSqlParserUtil.parse(sql);
        } catch (JSQLParserException e) {
            throw new IllegalArgumentException("SQL failed schema allowlist validation: " + e.getMessage(), e);
        }
        if (statement == null) {
            throw new IllegalArgumentException("SQL failed schema allowlist validation: empty statement");
        }

        List<String> referencedTables = new TablesNamesFinder().getTableList(statement);
        for (String reference : referencedTables) {
            String schema = extractSchema(reference);
            if (schema == null || !allowedSchemas.contains(schema)) {
                throw new IllegalArgumentException(
                        "Table reference '" + reference + "' is not schema-qualified with an allowed schema");
            }
        }
    }

    private static String extractSchema(String tableReference) {
        String unquoted = tableReference.replace("\"", "");
        int dot = unquoted.indexOf('.');
        if (dot <= 0 || dot == unquoted.length() - 1) {
            return null;
        }
        return unquoted.substring(0, dot).toUpperCase(Locale.ROOT);
    }
}
