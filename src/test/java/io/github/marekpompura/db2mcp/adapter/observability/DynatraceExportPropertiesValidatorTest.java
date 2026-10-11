package io.github.marekpompura.db2mcp.adapter.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

class DynatraceExportPropertiesValidatorTest {

    private static final DefaultApplicationArguments NO_ARGS = new DefaultApplicationArguments();

    @Test
    void run_doesNothing_whenDisabled() {
        DynatraceExportPropertiesValidator validator =
                new DynatraceExportPropertiesValidator(new DynatraceExportProperties(false, "", ""));

        assertThatCode(() -> validator.run(NO_ARGS)).doesNotThrowAnyException();
    }

    @Test
    void run_throws_whenEnabledWithBlankEnvironmentUrl() {
        DynatraceExportPropertiesValidator validator =
                new DynatraceExportPropertiesValidator(new DynatraceExportProperties(true, "", "some-token"));

        assertThatThrownBy(() -> validator.run(NO_ARGS))
                .isInstanceOf(DynatraceConfigurationException.class)
                .hasMessageContaining("db2mcp.dynatrace.environment-url");
    }

    @Test
    void run_throws_whenEnabledWithBlankApiToken() {
        DynatraceExportPropertiesValidator validator = new DynatraceExportPropertiesValidator(
                new DynatraceExportProperties(true, "https://abc12345.live.dynatrace.com", ""));

        assertThatThrownBy(() -> validator.run(NO_ARGS))
                .isInstanceOf(DynatraceConfigurationException.class)
                .hasMessageContaining("db2mcp.dynatrace.api-token");
    }

    @Test
    void run_throws_whenEnabledWithBothBlank() {
        DynatraceExportPropertiesValidator validator =
                new DynatraceExportPropertiesValidator(new DynatraceExportProperties(true, "", ""));

        assertThatThrownBy(() -> validator.run(NO_ARGS))
                .isInstanceOf(DynatraceConfigurationException.class)
                .hasMessageContaining("db2mcp.dynatrace.environment-url")
                .hasMessageContaining("db2mcp.dynatrace.api-token");
    }

    @Test
    void run_throws_whenEnvironmentUrlIsNotHttps() {
        DynatraceExportPropertiesValidator validator = new DynatraceExportPropertiesValidator(
                new DynatraceExportProperties(true, "http://abc12345.live.dynatrace.com", "some-token"));

        assertThatThrownBy(() -> validator.run(NO_ARGS))
                .isInstanceOf(DynatraceConfigurationException.class)
                .hasMessageContaining("db2mcp.dynatrace.environment-url")
                .hasMessageContaining("https://");
    }

    @Test
    void run_throws_whenEnvironmentUrlHasNoScheme() {
        DynatraceExportPropertiesValidator validator = new DynatraceExportPropertiesValidator(
                new DynatraceExportProperties(true, "abc12345.live.dynatrace.com", "some-token"));

        assertThatThrownBy(() -> validator.run(NO_ARGS)).isInstanceOf(DynatraceConfigurationException.class);
    }

    @Test
    void run_succeeds_whenEnabledWithValidConfig() {
        DynatraceExportPropertiesValidator validator = new DynatraceExportPropertiesValidator(
                new DynatraceExportProperties(true, "https://abc12345.live.dynatrace.com", "some-token"));

        assertThatCode(() -> validator.run(NO_ARGS)).doesNotThrowAnyException();
    }

    // The single most important test in this file: guards the one line in
    // DynatraceExportPropertiesValidator that could leak the API token into
    // a startup-failure stack trace if a future edit carelessly interpolates
    // properties.apiToken() into the exception message.
    @Test
    void run_exceptionMessage_neverContainsTheApiTokenValue() {
        String sentinelToken = "dt0c01.SENTINEL_TOKEN_VALUE_MUST_NOT_LEAK";
        // Valid token, but an invalid (non-https) URL - still throws, for a
        // reason unrelated to the token, so the token's presence in the
        // config is exercised while checking it never surfaces in the
        // message.
        DynatraceExportPropertiesValidator validator = new DynatraceExportPropertiesValidator(
                new DynatraceExportProperties(true, "http://abc12345.live.dynatrace.com", sentinelToken));

        DynatraceConfigurationException exception =
                catchThrowableOfType(() -> validator.run(NO_ARGS), DynatraceConfigurationException.class);

        assertThat(exception).isNotNull();
        assertThat(exception.getMessage()).doesNotContain(sentinelToken);
    }
}
