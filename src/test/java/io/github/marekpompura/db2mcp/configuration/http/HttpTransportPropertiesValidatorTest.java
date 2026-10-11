package io.github.marekpompura.db2mcp.configuration.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

class HttpTransportPropertiesValidatorTest {

    private static final DefaultApplicationArguments NO_ARGS = new DefaultApplicationArguments();

    private static MockEnvironment servletEnvironment() {
        return new MockEnvironment().withProperty("spring.main.web-application-type", "servlet");
    }

    @Test
    void run_doesNothing_whenDisabled() {
        // Deliberately a fresh MockEnvironment with no web-application-type set
        // at all - disabled should short-circuit before this is even read.
        HttpTransportPropertiesValidator validator =
                new HttpTransportPropertiesValidator(new HttpTransportProperties(false, ""), new MockEnvironment());

        assertThatCode(() -> validator.run(NO_ARGS)).doesNotThrowAnyException();
    }

    @Test
    void run_throws_whenEnabledWithBlankBearerToken() {
        HttpTransportPropertiesValidator validator =
                new HttpTransportPropertiesValidator(new HttpTransportProperties(true, ""), servletEnvironment());

        assertThatThrownBy(() -> validator.run(NO_ARGS))
                .isInstanceOf(HttpTransportConfigurationException.class)
                .hasMessageContaining("db2mcp.http.bearer-token");
    }

    @Test
    void run_throws_whenBearerTokenIsTooShort() {
        // SR-002 (docs/security/http-transport-security-review-2026-08-29.md):
        // blankness alone isn't enough - a trivially guessable one-character
        // token must also be rejected.
        HttpTransportPropertiesValidator validator =
                new HttpTransportPropertiesValidator(new HttpTransportProperties(true, "x"), servletEnvironment());

        assertThatThrownBy(() -> validator.run(NO_ARGS))
                .isInstanceOf(HttpTransportConfigurationException.class)
                .hasMessageContaining("db2mcp.http.bearer-token")
                .hasMessageContaining("16");
    }

    @Test
    void run_throws_whenWebApplicationTypeIsNotServlet() {
        // enabled=true with a real token, but web-application-type left at
        // its unset/default value - exactly the two-knob footgun this
        // second check exists to catch.
        HttpTransportPropertiesValidator validator = new HttpTransportPropertiesValidator(
                new HttpTransportProperties(true, "a-sufficiently-long-token-value"), new MockEnvironment());

        assertThatThrownBy(() -> validator.run(NO_ARGS))
                .isInstanceOf(HttpTransportConfigurationException.class)
                .hasMessageContaining("spring.main.web-application-type");
    }

    @Test
    void run_throws_whenWebApplicationTypeIsExplicitlyNone() {
        HttpTransportPropertiesValidator validator = new HttpTransportPropertiesValidator(
                new HttpTransportProperties(true, "a-sufficiently-long-token-value"),
                new MockEnvironment().withProperty("spring.main.web-application-type", "none"));

        assertThatThrownBy(() -> validator.run(NO_ARGS))
                .isInstanceOf(HttpTransportConfigurationException.class)
                .hasMessageContaining("spring.main.web-application-type");
    }

    @Test
    void run_succeeds_whenEnabledWithValidConfig() {
        HttpTransportPropertiesValidator validator = new HttpTransportPropertiesValidator(
                new HttpTransportProperties(true, "a-sufficiently-long-token-value"), servletEnvironment());

        assertThatCode(() -> validator.run(NO_ARGS)).doesNotThrowAnyException();
    }

    // The single most important test in this file: guards the one line in
    // HttpTransportPropertiesValidator that could leak the bearer token into
    // a startup-failure stack trace if a future edit carelessly interpolates
    // properties.bearerToken() into the exception message.
    @Test
    void run_exceptionMessage_neverContainsTheBearerTokenValue() {
        String sentinelToken = "SENTINEL_TOKEN_VALUE_MUST_NOT_LEAK";
        // Valid, non-blank token, but web-application-type left unset - still
        // throws, for a reason unrelated to the token's own value, so the
        // token's presence in the config is exercised while checking it
        // never surfaces in the message.
        HttpTransportPropertiesValidator validator = new HttpTransportPropertiesValidator(
                new HttpTransportProperties(true, sentinelToken), new MockEnvironment());

        HttpTransportConfigurationException exception =
                catchThrowableOfType(() -> validator.run(NO_ARGS), HttpTransportConfigurationException.class);

        assertThat(exception).isNotNull();
        assertThat(exception.getMessage()).doesNotContain(sentinelToken);
    }
}
