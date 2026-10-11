package io.github.marekpompura.db2mcp.configuration.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.marekpompura.db2mcp.application.port.out.CapabilityPort;
import io.github.marekpompura.db2mcp.bootstrap.Db2McpServerApplication;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.context.WebApplicationContext;

/**
 * {@code db2mcp.http.enabled} defaults to {@code false} (thread 6, ADR
 * 0037) - proves the real context, booted with no HTTP properties set at
 * all, genuinely never creates any of {@code HttpTransportConfig}'s beans,
 * and - the stronger claim - that the {@code ApplicationContext} itself is
 * not even a {@link WebApplicationContext}, i.e. no servlet container ever
 * starts and no port ever opens. This is the actual "no network exposure"
 * proof ADR 0001 depends on, not merely "the HTTP beans behave as no-ops."
 *
 * <p>{@code @DirtiesContext}: see {@code DynatraceDisabledIntegrationTest}'s
 * own Javadoc for why - accumulated, never-closed {@code @SpringBootTest}
 * contexts each holding a live non-daemon metrics-publisher thread is what
 * eventually forces Surefire to kill its own fork JVM, cutting off
 * JaCoCo's coverage dump.
 */
@SpringBootTest(classes = Db2McpServerApplication.class)
@DirtiesContext
class HttpTransportDisabledIntegrationTest {

    @MockitoBean
    private McpServerTransportProvider mcpServerTransportProvider;

    @Autowired
    private CapabilityPort capabilityPort;

    @Autowired
    private ApplicationContext applicationContext;

    @DynamicPropertySource
    static void unreachableDatasource(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.datasource.url", () -> "jdbc:h2:mem:http_transport_disabled_test;DB_CLOSE_DELAY=-1");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("db2mcp.allowed-schemas[0]", () -> "ITSM");
        // db2mcp.http.* and spring.main.web-application-type deliberately
        // left unset - proves the defaults (enabled=false, none) are what a
        // real, unconfigured deployment gets.
    }

    @Test
    void whenDisabled_noHttpBeanExistsAndTheContextIsNotAWebApplicationContext() {
        capabilityPort.capabilities(); // sanity: the context is otherwise healthy

        // Not asserting HttpTransportConfig.class is absent - @Configuration
        // classes are always registered as beans regardless of whether the
        // @Bean methods inside are conditionally gated off; @ConditionalOnProperty
        // gates the factory methods, not the enclosing class.
        assertThat(applicationContext.getBeanNamesForType(
                        io.modelcontextprotocol.spec.McpStreamableServerTransportProvider.class))
                .isEmpty();
        assertThat(applicationContext.getBeansOfType(jakarta.servlet.Filter.class)).isEmpty();
        assertThat(applicationContext).isNotInstanceOf(WebApplicationContext.class);
    }
}
