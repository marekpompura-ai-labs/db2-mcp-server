package io.github.marekpompura.db2mcp.configuration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.marekpompura.db2mcp.adapter.observability.DynatraceConfigurationException;
import io.github.marekpompura.db2mcp.adapter.observability.DynatraceExportProperties;
import io.github.marekpompura.db2mcp.adapter.observability.DynatraceExportPropertiesValidator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Proves {@link DynatraceExportPropertiesValidator} actually stops a real
 * Spring Boot startup sequence (thread 5, ADR 0036), not just the
 * isolated validator class in a unit test ({@code
 * DynatraceExportPropertiesValidatorTest} covers that already).
 *
 * <p>Deliberately does NOT boot the full {@code Db2McpServerApplication} -
 * that pulls in {@code StdioLifecycleConfig}'s real transport bean, whose
 * background stdin-reading thread is a known hang risk in a test that
 * never closes {@code System.in} ({@link ObservabilityExportIntegrationTest}'s
 * own comment documents this exact failure mode). A minimal {@code
 * @SpringBootApplication} scoped to just this test package still exercises
 * the real thing under test - {@code SpringApplication.run(...)}'s genuine
 * {@code ApplicationRunner} invocation - without that unrelated risk.
 */
class DynatraceStartupFailureIntegrationTest {

    // scanBasePackages deliberately scoped to just adapter.observability -
    // this test class itself lives in the configuration package, and the
    // default @SpringBootApplication component scan (rooted at the
    // annotated class's own package) would otherwise also pick up real
    // production classes from io.github.marekpompura.db2mcp.configuration
    // (e.g. EffectiveConfigReporter), pulling in unrelated dependencies
    // this minimal app was never meant to wire.
    @SpringBootApplication(scanBasePackages = "io.github.marekpompura.db2mcp.adapter.observability")
    @ConfigurationPropertiesScan("io.github.marekpompura.db2mcp.adapter.observability")
    static class MinimalDynatraceValidationApp {}

    @Test
    void enabledWithMissingApiToken_stopsRealSpringBootStartup() {
        // Command-line-style args (SpringApplication.run(String...)), not
        // .properties(Map<String,Object>) - that overload adds DEFAULT
        // (lowest-priority) properties, which application.yml's real
        // db2mcp.dynatrace.enabled: false (on the test classpath via main
        // resources) would still win over - confirmed directly, a real
        // property-precedence bug caught by a diagnostic run before this
        // test was trusted, not assumed. Command-line args are Spring
        // Boot's highest-priority property source, genuinely overriding
        // application.yml.
        //
        // The exception surfaces directly from SpringApplication.run(),
        // not wrapped in a context-loading exception (that wrapping is
        // specific to Spring *Test*'s context caching, e.g.
        // DynatraceObservabilityIntegrationTest's @SpringBootTest cases) -
        // confirmed directly, not assumed.
        assertThatThrownBy(() -> {
                    ConfigurableApplicationContext context = new SpringApplicationBuilder(
                                    MinimalDynatraceValidationApp.class)
                            .web(WebApplicationType.NONE)
                            .run(
                                    "--db2mcp.dynatrace.enabled=true",
                                    "--db2mcp.dynatrace.environment-url=https://abc12345.live.dynatrace.com",
                                    "--db2mcp.dynatrace.api-token=");
                    context.close();
                })
                .isInstanceOf(DynatraceConfigurationException.class)
                .hasMessageContaining("db2mcp.dynatrace.api-token");
    }

    @Test
    void disabled_realSpringBootStartupSucceeds() {
        // No overrides - db2mcp.dynatrace.enabled defaults to false
        // (application.yml), so real Spring Boot startup (including
        // DynatraceExportPropertiesValidator's real ApplicationRunner
        // invocation) succeeds without needing endpoint/token.
        ConfigurableApplicationContext context = new SpringApplicationBuilder(MinimalDynatraceValidationApp.class)
                .web(WebApplicationType.NONE)
                .run();
        try {
            org.assertj.core.api.Assertions.assertThat(context.getBean(DynatraceExportProperties.class).enabled())
                    .isFalse();
        } finally {
            context.close();
        }
    }
}
