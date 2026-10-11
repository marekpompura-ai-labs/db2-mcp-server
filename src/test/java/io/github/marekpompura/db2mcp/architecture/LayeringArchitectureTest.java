package io.github.marekpompura.db2mcp.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import io.github.marekpompura.db2mcp.domain.error.DatabaseErrorClassifier;

/**
 * Enforces the dependency rule from the master brief (docs/master-brief.md
 * section 4.2): dependencies point inward, tool -> application -> policy ->
 * adapter, never the reverse and never skipped. See ADR 0005. Phase 5
 * (docs/implementation-plan.md) expanded this from a single JDBC-specific
 * rule to the full set below - each was verified true of the actual
 * codebase (via grep) before being locked in as a rule, not written on
 * spec and hoped to pass.
 */
@AnalyzeClasses(packages = "io.github.marekpompura.db2mcp", importOptions = ImportOption.DoNotIncludeTests.class)
class LayeringArchitectureTest {

    // @ArchTest on methods, not fields: field-based @ArchTest silently fails
    // to execute under Maven Surefire 3.5.3+ (https://github.com/TNG/ArchUnit/issues/1442).

    @ArchTest
    void mcp_tool_layer_must_not_access_jdbc_directly(JavaClasses classes) {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..mcp.tool..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework.jdbc..", "javax.sql..", "java.sql..");
        rule.check(classes);
    }

    @ArchTest
    void mcp_tool_layer_must_not_access_adapter_layer_at_all(JavaClasses classes) {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..mcp.tool..")
                .should().dependOnClassesThat()
                .resideInAPackage("..adapter..");
        rule.check(classes);
    }

    @ArchTest
    void application_layer_must_not_depend_on_adapter_layer(JavaClasses classes) {
        // Ports (application) are implemented by adapters, never the reverse -
        // the dependency inversion this project's whole layering rests on.
        ArchRule rule = noClasses()
                .that().resideInAPackage("..application..")
                .should().dependOnClassesThat()
                .resideInAPackage("..adapter..");
        rule.check(classes);
    }

    @ArchTest
    void policy_layer_must_not_depend_on_adapter_layer(JavaClasses classes) {
        // Validators (policy) are pure logic and configuration - no reason to
        // reach into adapter internals.
        ArchRule rule = noClasses()
                .that().resideInAPackage("..policy..")
                .should().dependOnClassesThat()
                .resideInAPackage("..adapter..");
        rule.check(classes);
    }

    @ArchTest
    void application_layer_must_not_depend_on_mcp_layer(JavaClasses classes) {
        // Outbound ports (application) express intent in domain-owned types,
        // never MCP transport DTOs (database-portability extension section 4,
        // ADR 0027) - the reverse of the direction MCP tools are allowed to
        // depend inward. Proven able to fail before this rule existed:
        // MetadataService/QueryService (now MetadataPort/QueryPort) returned
        // mcp.contract.* types directly.
        ArchRule rule = noClasses()
                .that().resideInAPackage("..application..")
                .should().dependOnClassesThat()
                .resideInAPackage("..mcp..");
        rule.check(classes);
    }

    @ArchTest
    void database_error_classifiers_must_live_in_an_adapter_package(JavaClasses classes) {
        // Db2SqlStateClassifier implements the database-neutral
        // DatabaseErrorClassifier port (domain.error) but encodes DB2-specific
        // SQLSTATE knowledge, so its implementation belongs in an adapter
        // package, not domain (database-portability extension section 10,
        // ADR 0027) - a future Oracle/PostgreSQL classifier would land the
        // same way, under its own adapter package.
        ArchRule rule = classes()
                .that().implement(DatabaseErrorClassifier.class)
                .should().resideInAPackage("..adapter..");
        rule.check(classes);
    }

    @ArchTest
    void domain_layer_must_not_depend_on_any_other_layer(JavaClasses classes) {
        // The innermost, most stable layer - AuditEvent/PolicyDecisionReason
        // should never need to know about tools, application, policy, or
        // adapters.
        ArchRule rule = noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..mcp..", "..application..", "..policy..", "..adapter..", "..configuration..");
        rule.check(classes);
    }
}
