package io.github.marekpompura.db2mcp.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.marekpompura.db2mcp.application.port.out.CapabilityPort;
import io.github.marekpompura.db2mcp.bootstrap.Db2McpServerApplication;
import io.micrometer.core.instrument.MeterRegistry;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * {@code db2mcp.dynatrace.enabled} defaults to {@code false} (thread 5,
 * ADR 0036) - proves the real context, booted with no Dynatrace properties
 * set at all, genuinely never creates {@code dynatraceSpanProcessor}/
 * {@code dynatraceMeterRegistry}, not merely that they'd behave as no-ops
 * if they existed. {@code @ConditionalOnProperty} evaluates before the
 * {@code @Bean} factory method ever runs, so this is a real absence, not
 * an inference from behavior.
 *
 * <p>{@code @DirtiesContext} forces Spring to close this context right
 * after this class's tests finish, instead of leaving it cached
 * indefinitely - Micrometer's {@code PushMeterRegistry} publisher thread
 * (the real {@code LoggingMeterRegistry} this context wires) is not a
 * daemon thread, so an uncached context left open accumulates a live
 * non-daemon thread that never stops on its own. With several other
 * {@code @SpringBootTest} classes each creating their own distinct cached
 * context in the same Surefire fork (none reused - each has a genuinely
 * different configuration), enough of these accumulate to keep the fork
 * JVM alive well past Surefire's patience for a clean exit, forcing a
 * kill that cuts off JaCoCo's shutdown-hook-based coverage dump for
 * whatever hadn't been flushed yet (confirmed root cause of intermittent
 * 0.00%-coverage CI failures on `policy.*` packages).
 */
@SpringBootTest(classes = Db2McpServerApplication.class)
@DirtiesContext
class DynatraceDisabledIntegrationTest {

    @MockitoBean
    private McpServerTransportProvider mcpServerTransportProvider;

    @Autowired
    private CapabilityPort capabilityPort;

    @Autowired
    private ApplicationContext applicationContext;

    @DynamicPropertySource
    static void unreachableDatasource(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.datasource.url", () -> "jdbc:h2:mem:dynatrace_disabled_test;DB_CLOSE_DELAY=-1");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("db2mcp.allowed-schemas[0]", () -> "ITSM");
        // db2mcp.dynatrace.* deliberately left unset - proves the default
        // (enabled=false) is what a real, unconfigured deployment gets.
    }

    @Test
    void whenDisabled_noDynatraceBeanExistsInTheContext() {
        capabilityPort.capabilities(); // sanity: the context is otherwise healthy

        assertThat(applicationContext.getBeanNamesForType(SpanProcessor.class))
                .doesNotContain("dynatraceSpanProcessor");
        assertThat(applicationContext.getBeanNamesForType(MeterRegistry.class))
                .doesNotContain("dynatraceMeterRegistry");
    }
}
