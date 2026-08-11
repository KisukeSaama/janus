package io.janus.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import jakarta.servlet.FilterChain;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import tools.jackson.databind.ObjectMapper;

class ClientRateLimitFilterTest {
    private static final String CLIENT = "203.0.113.10";
    private static final String OTHER_CLIENT = "203.0.113.11";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    /** Two permits a minute with a burst of two: the third call in a row has nothing left to take. */
    private ClientRateLimitFilter filter(int adminPerMinute, int gatewayPerMinute) {
        return new ClientRateLimitFilter(
                adminPerMinute, adminPerMinute, gatewayPerMinute, gatewayPerMinute, registry, new ObjectMapper());
    }

    private MockHttpServletResponse call(ClientRateLimitFilter filter, String uri, String client, FilterChain chain)
            throws Exception {
        var request = new MockHttpServletRequest("GET", uri);
        request.setRemoteAddr(client);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    @Test
    void refusesOnceTheClientHasSpentItsBurst() throws Exception {
        var filter = filter(2, 2);
        var chain = mock(FilterChain.class);

        assertThat(call(filter, "/api/admin/providers", CLIENT, chain).getStatus())
                .isEqualTo(200);
        assertThat(call(filter, "/api/admin/providers", CLIENT, chain).getStatus())
                .isEqualTo(200);

        var refused = call(filter, "/api/admin/providers", CLIENT, chain);
        assertThat(refused.getStatus()).isEqualTo(429);
        assertThat(refused.getHeader("Retry-After")).isNotNull();
        assertThat(refused.getContentAsString()).contains("Too many requests");
        verify(chain, times(2)).doFilter(any(), any());
    }

    @Test
    void oneClientCannotSpendAnothersAllowance() throws Exception {
        var filter = filter(1, 1);
        var chain = mock(FilterChain.class);

        call(filter, "/api/admin/providers", CLIENT, chain);
        assertThat(call(filter, "/api/admin/providers", CLIENT, chain).getStatus())
                .isEqualTo(429);
        assertThat(call(filter, "/api/admin/providers", OTHER_CLIENT, chain).getStatus())
                .isEqualTo(200);
    }

    /** The console and the gateway are metered apart, so a busy caller cannot lock operators out. */
    @Test
    void theTwoSurfacesHaveSeparateAllowances() throws Exception {
        var filter = filter(1, 1);
        var chain = mock(FilterChain.class);

        call(filter, "/gateway/example/v1/items", CLIENT, chain);
        assertThat(call(filter, "/gateway/example/v1/items", CLIENT, chain).getStatus())
                .isEqualTo(429);
        assertThat(call(filter, "/api/admin/providers", CLIENT, chain).getStatus())
                .isEqualTo(200);
    }

    @Test
    void healthIsNeverRefusedSoAFloodCannotRestartTheInstance() throws Exception {
        var filter = filter(1, 1);
        var chain = mock(FilterChain.class);

        for (int i = 0; i < 5; i++) {
            assertThat(call(filter, "/actuator/health", CLIENT, chain).getStatus())
                    .isEqualTo(200);
        }
        verify(chain, times(5)).doFilter(any(), any());
    }

    @Test
    void refusalsAreCountedRatherThanAuditedOrLogged() throws Exception {
        var filter = filter(1, 1);
        var chain = mock(FilterChain.class);

        call(filter, "/api/admin/providers", CLIENT, chain);
        call(filter, "/api/admin/providers", CLIENT, chain);

        assertThat(registry.get("janus.ratelimit.rejected")
                        .tag("surface", "admin")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void aCeilingOfZeroLeavesTheSurfaceUnmetered() throws Exception {
        var filter = filter(0, 0);
        var chain = mock(FilterChain.class);

        for (int i = 0; i < 20; i++) {
            assertThat(call(filter, "/api/admin/providers", CLIENT, chain).getStatus())
                    .isEqualTo(200);
        }
    }
}
