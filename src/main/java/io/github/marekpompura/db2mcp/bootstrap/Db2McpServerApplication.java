package io.github.marekpompura.db2mcp.bootstrap;

import org.slf4j.bridge.SLF4JBridgeHandler;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.dynatrace.DynatraceMetricsExportAutoConfiguration;

/**
 * {@code DynatraceMetricsExportAutoConfiguration} is excluded deliberately
 * (thread 5, ADR 0036): merely having {@code micrometer-registry-dynatrace}
 * on the classpath makes Spring Boot try to auto-register its own {@code
 * dynatraceMeterRegistry} bean (driven by {@code management.dynatrace.*}
 * properties this project doesn't set), colliding by name with {@code
 * DynatraceObservabilityConfig}'s own hand-wired bean of the same name -
 * confirmed directly, a real {@code BeanDefinitionOverrideException} at
 * context startup, not a hypothetical. This project already deliberately
 * hand-wires its exporters rather than relying on Spring Boot's own
 * vendor-registry autoconfiguration (ADR 0030's same choice for the
 * logging exporters), so excluding Boot's competing autoconfiguration here
 * is consistent, not a workaround.
 */
@SpringBootApplication(
        scanBasePackages = "io.github.marekpompura.db2mcp",
        exclude = DynatraceMetricsExportAutoConfiguration.class)
@ConfigurationPropertiesScan("io.github.marekpompura.db2mcp")
public class Db2McpServerApplication {

    public static void main(String[] args) {
        // OpenTelemetry's LoggingSpanExporter (CR-010, ADR 0030) logs via
        // java.util.logging, not SLF4J. jul-to-slf4j is on the classpath
        // transitively (spring-boot-starter-logging) but the bridge is never
        // installed automatically - without this, every span export call is a
        // real, verified no-op: nothing appears anywhere, not even a dropped
        // warning. Installed as early as possible, per SLF4J's own guidance.
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();
        SpringApplication.run(Db2McpServerApplication.class, args);
    }
}
