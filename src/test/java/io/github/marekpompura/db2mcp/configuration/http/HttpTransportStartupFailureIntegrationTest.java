package io.github.marekpompura.db2mcp.configuration.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ApplicationContextException;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Proves {@link HttpTransportPropertiesValidator} actually stops a real
 * Spring Boot startup sequence (thread 6, ADR 0037), not just the isolated
 * validator class in a unit test ({@code HttpTransportPropertiesValidatorTest}
 * covers that already). Mirrors {@code DynatraceStartupFailureIntegrationTest}'s
 * exact pattern.
 *
 * <p>Deliberately does NOT boot the full {@code Db2McpServerApplication} -
 * that pulls in {@code StdioLifecycleConfig}'s real transport bean, whose
 * background stdin-reading thread is a known hang risk in a test that never
 * closes {@code System.in}. A minimal {@code @SpringBootApplication} scoped
 * to just this test package still exercises the real startup sequence
 * without that unrelated risk; the scoped component scan is also why
 * booting it as a servlet application is safe here.
 *
 * <p>The failure case runs as a genuine {@link WebApplicationType#SERVLET}
 * application. That matters: the defect this guards against was that
 * bearer-token validation ran only in an {@code ApplicationRunner}, which
 * executes *after* Spring Boot has already started the embedded container
 * during context refresh. Only a servlet context can demonstrate that the
 * check now fires early enough to prevent the port being bound at all.
 */
class HttpTransportStartupFailureIntegrationTest {

    // scanBasePackages deliberately scoped to just configuration.http - this
    // test class itself lives in that same package, and the default
    // @SpringBootApplication component scan (rooted at the annotated
    // class's own package) would otherwise also pick up
    // BearerTokenAuthenticationFilter/HttpTransportConfig's servlet-related
    // beans, which need a real servlet environment this minimal NONE-typed
    // app never provides.
    @SpringBootApplication(scanBasePackages = "io.github.marekpompura.db2mcp.configuration.http")
    @ConfigurationPropertiesScan("io.github.marekpompura.db2mcp.configuration.http")
    static class MinimalHttpValidationApp {}

    @Test
    void enabledWithMissingBearerToken_stopsRealSpringBootStartup() {
        // Command-line-style args (SpringApplication.run(String...)), not
        // .properties(Map<String,Object>) - that overload adds DEFAULT
        // (lowest-priority) properties, which application.yml's real
        // db2mcp.http.enabled: false (on the test classpath via main
        // resources) would still win over. Command-line args are Spring
        // Boot's highest-priority property source, genuinely overriding
        // application.yml.
        //
        // spring.main.web-application-type is also overridden here to
        // "servlet" so the test fails specifically on the blank-token
        // check, not the web-application-type check - HttpTransportPropertiesValidatorTest
        // already covers that second check in isolation.
        assertThatThrownBy(() -> {
                    ConfigurableApplicationContext context = new SpringApplicationBuilder(
                                    MinimalHttpValidationApp.class)
                            // SERVLET on the builder itself, not NONE with an
                            // arg overriding it (closure review P1-1). Relying
                            // on the argument left it ambiguous whether a
                            // servlet context was genuinely created, which is
                            // the entire point of this test: the blank-token
                            // check has to fire while the web server is being
                            // started, not after it has bound a port.
                            .web(WebApplicationType.SERVLET)
                            .run(
                                    "--db2mcp.http.enabled=true",
                                    "--spring.main.web-application-type=servlet",
                                    // Ephemeral port, not application.yml's
                                    // fixed 8080. The assertion is that no
                                    // port is bound at all, so in a passing
                                    // run this is never used - but a fixed
                                    // port would make a regression collide
                                    // with whatever else holds 8080 on the
                                    // machine, turning a clear failure into
                                    // a confusing one.
                                    "--server.port=0",
                                    "--db2mcp.http.bearer-token=");
                    context.close();
                })
                // BOTH halves are asserted, because each carries different
                // evidence (HTTP-P1-1, closure review P1-1):
                //
                // - the top-level ApplicationContextException("Unable to start
                //   web server") proves the servlet container never finished
                //   starting, so no port was ever bound;
                // - the root cause proves it stopped for the intended reason,
                //   the blank bearer token, and not something incidental.
                //
                // Before the fix, the blank-token check lived only in
                // HttpTransportPropertiesValidator - an ApplicationRunner,
                // which runs *after* context refresh. Tomcat had already bound
                // 127.0.0.1:8080 by the time it threw, and a blank expected
                // token authenticates every request, since
                // MessageDigest.isEqual(empty, empty) is true.
                .isInstanceOf(ApplicationContextException.class)
                .hasMessageContaining("Unable to start web server")
                .rootCause()
                .isInstanceOf(HttpTransportConfigurationException.class)
                .hasMessageContaining("db2mcp.http.bearer-token");
    }

    @Test
    void disabled_realSpringBootStartupSucceeds() {
        // No overrides - db2mcp.http.enabled defaults to false
        // (application.yml), so real Spring Boot startup (including
        // HttpTransportPropertiesValidator's real ApplicationRunner
        // invocation) succeeds without needing a bearer token.
        ConfigurableApplicationContext context =
                new SpringApplicationBuilder(MinimalHttpValidationApp.class).web(WebApplicationType.NONE).run();
        try {
            assertThat(context.getBean(HttpTransportProperties.class).enabled()).isFalse();
        } finally {
            context.close();
        }
    }
}
