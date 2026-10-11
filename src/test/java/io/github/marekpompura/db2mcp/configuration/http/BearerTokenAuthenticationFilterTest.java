package io.github.marekpompura.db2mcp.configuration.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;

class BearerTokenAuthenticationFilterTest {

    private static final String REAL_TOKEN = "correct-token-value";

    private final BearerTokenAuthenticationFilter filter = new BearerTokenAuthenticationFilter(REAL_TOKEN);

    // HTTP-P1-1 / HTTP-P1-2 (2026-09-21 dedicated HTTP review). The filter
    // must refuse to exist with a token that cannot gate anything, because
    // it is constructed during context refresh - before the embedded
    // servlet container binds its port. HttpTransportPropertiesValidator
    // runs later, as an ApplicationRunner, so it cannot close that window.
    @Test
    void constructor_rejectsBlankToken_soNoUngatedFilterCanExist() {
        assertThatThrownBy(() -> new BearerTokenAuthenticationFilter(""))
                .isInstanceOf(HttpTransportConfigurationException.class)
                .hasMessageContaining("db2mcp.http.bearer-token")
                .hasMessageContaining("blank");
    }

    @Test
    void constructor_rejectsWhitespaceOnlyToken() {
        assertThatThrownBy(() -> new BearerTokenAuthenticationFilter("   "))
                .isInstanceOf(HttpTransportConfigurationException.class);
    }

    @Test
    void constructor_rejectsTokenShorterThanTheMinimum() {
        assertThatThrownBy(() -> new BearerTokenAuthenticationFilter("short"))
                .isInstanceOf(HttpTransportConfigurationException.class)
                .hasMessageContaining("shorter than the required minimum");
    }

    @Test
    void constructor_rejectionMessage_neverContainsTheTokenValue() {
        String sentinel = "sentinel-token-value-that-must-not-leak";
        assertThatThrownBy(() -> new BearerTokenAuthenticationFilter(sentinel.substring(0, 5)))
                .isInstanceOf(HttpTransportConfigurationException.class)
                .hasMessageNotContaining(sentinel.substring(0, 5));
    }


    @Test
    void doFilter_rejects_whenAuthorizationHeaderIsMissing() throws Exception {
        assertRejected(null);
    }

    @Test
    void doFilter_rejects_whenSchemeIsNotBearer() throws Exception {
        assertRejected("Basic " + REAL_TOKEN);
    }

    @Test
    void doFilter_rejects_wrongTokenSameLengthAsReal() throws Exception {
        // Guards against an accidental substring/prefix-only comparison bug -
        // a same-length wrong token must still be rejected.
        String wrongTokenSameLength = "x".repeat(REAL_TOKEN.length());
        assertRejected("Bearer " + wrongTokenSameLength);
    }

    @Test
    void doFilter_rejects_wrongTokenDifferentLength() throws Exception {
        assertRejected("Bearer wrong");
    }

    @Test
    void doFilter_allows_whenTokenIsCorrect() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getHeader("Authorization")).thenReturn("Bearer " + REAL_TOKEN);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verifyNoInteractions(response);
    }

    @Test
    void rejectionResponse_neverContainsTheConfiguredTokenSubstring() throws Exception {
        String sentinelToken = "SENTINEL_TOKEN_MUST_NOT_LEAK";
        BearerTokenAuthenticationFilter sentinelFilter = new BearerTokenAuthenticationFilter(sentinelToken);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getHeader("Authorization")).thenReturn("Bearer wrong-token");
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/mcp");
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");
        StringWriter body = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        sentinelFilter.doFilter(request, response, chain);

        assertThat(body.toString()).doesNotContain(sentinelToken);
    }

    private void assertRejected(String authorizationHeader) throws IOException, ServletException {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getHeader("Authorization")).thenReturn(authorizationHeader);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/mcp");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        StringWriter body = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(any(), any());
        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(response).setHeader("WWW-Authenticate", "Bearer");
        assertThat(body.toString()).doesNotContain(REAL_TOKEN);
    }
}
