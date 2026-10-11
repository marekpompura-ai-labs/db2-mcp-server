package io.github.marekpompura.db2mcp.policy.sql;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SchemaAllowlistValidatorTest {

    private final SchemaAllowlistValidator validator =
            new SchemaAllowlistValidator(new QueryPolicyProperties(List.of("ITSM")));

    @ParameterizedTest
    @ValueSource(
            strings = {
                "SELECT * FROM ITSM.TICKET",
                "select * from itsm.ticket", // case-insensitive schema match
                "SELECT t.TICKET_ID FROM ITSM.TICKET t JOIN ITSM.CUSTOMER c ON c.CUSTOMER_ID = t.CUSTOMER_ID",
                "SELECT * FROM \"ITSM\".\"TICKET\""
            })
    void validate_acceptsReferencesInAllowedSchema(String sql) {
        assertThatCode(() -> validator.validate(sql)).doesNotThrowAnyException();
    }

    @Test
    void validate_rejectsReferenceOutsideAllowedSchema() {
        assertThatThrownBy(() -> validator.validate("SELECT * FROM OTHER_SCHEMA.SECRET"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validate_rejectsUnqualifiedTableReference() {
        // Cannot verify which schema CURRENT SCHEMA would resolve this to - reject rather than guess.
        assertThatThrownBy(() -> validator.validate("SELECT * FROM TICKET"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validate_rejectsWhenOneOfMultipleTablesIsOutsideAllowlist() {
        assertThatThrownBy(() -> validator.validate("SELECT * FROM ITSM.TICKET t, OTHER_SCHEMA.SECRET s"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validate_emptyAllowlistRejectsEverything() {
        SchemaAllowlistValidator denyAll = new SchemaAllowlistValidator(new QueryPolicyProperties(List.of()));
        assertThatThrownBy(() -> denyAll.validate("SELECT * FROM ITSM.TICKET"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void queryPolicyProperties_treatsNullAllowedSchemasAsEmpty() {
        QueryPolicyProperties properties = new QueryPolicyProperties(null);
        org.assertj.core.api.Assertions.assertThat(properties.allowedSchemas()).isEmpty();
    }

    // Adversarial catalogue: quoted or case-manipulated identifiers used to evade
    // the allowlist rather than legitimately reference an allowed schema (the
    // earlier acceptsReferencesInAllowedSchema test only covers the legitimate
    // case). Quoting a disallowed schema must not bypass the check.
    @ParameterizedTest
    @ValueSource(
            strings = {
                "SELECT * FROM \"OTHER_SCHEMA\".\"SECRET\"",
                "select * from other_schema.secret",
                "SELECT * FROM \"Other_Schema\".\"Secret\""
            })
    void validate_rejectsQuotedOrCaseManipulatedDisallowedSchema(String sql) {
        assertThatThrownBy(() -> validator.validate(sql)).isInstanceOf(IllegalArgumentException.class);
    }

    // Adversarial catalogue: attempts to enumerate system catalogs outside policy.
    // Named explicitly in the master brief distinctly from "any other schema" -
    // worth its own case even though the mechanism is the same allowlist check.
    @ParameterizedTest
    @ValueSource(strings = {"SELECT * FROM SYSCAT.TABLES", "SELECT * FROM SYSIBM.SYSTABLES"})
    void validate_rejectsSystemCatalogEnumeration(String sql) {
        assertThatThrownBy(() -> validator.validate(sql)).isInstanceOf(IllegalArgumentException.class);
    }

    // In the real guard chain (Db2QueryAdapter) SqlStructuralValidator runs first,
    // so this validator normally never sees genuinely unparseable SQL - but it's a
    // standalone component and must not be trusted to only ever receive
    // already-validated input; both defensive paths are exercised directly.
    @Test
    void validate_rejectsSqlThatFailsToParse() {
        assertThatThrownBy(() -> validator.validate("SELECT FROM WHERE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SQL failed schema allowlist validation");
    }

    @Test
    void validate_rejectsEmptyStatement() {
        // CCJSqlParserUtil.parse("") returns null rather than throwing (ADR 0007's
        // finding, reconfirmed here as this validator's own defensive branch).
        //
        // Asserts this project's own prefix, not JSqlParser's wording (#100):
        // the original assertion matched "empty statement", which 5.4 reworded
        // to "SQL statement must not be null or empty." A test that pins a
        // dependency's message text breaks on every bump while telling us
        // nothing about our own behaviour - what matters is that an empty
        // statement is rejected by this validator, not how the parser phrases
        // its complaint.
        assertThatThrownBy(() -> validator.validate(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SQL failed schema allowlist validation");
    }
}
