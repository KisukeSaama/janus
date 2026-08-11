package io.janus.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.catalina.filters.RemoteIpFilter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.*;

/**
 * Guards the setting every rate limit and every throttle rests on: which address a request is
 * counted against.
 *
 * <p>Getting it wrong fails quietly in both directions. Trust too little and the reverse proxy
 * becomes the client, so ten bad sign-ins lock every operator out at once. Trust too much and a
 * caller's own {@code X-Forwarded-For} is believed, so it can present a fresh identity per request
 * and guess passwords and API keys without limit. Neither shows up as an error.
 *
 * <p>The checks run Tomcat's own implementation of the algorithm the valve applies, configured from
 * the shipped {@code application.yml}, so they fail if the value moves, changes format, or stops
 * being read.
 */
class TrustedProxyConfigurationTest {

    private final String internalProxies = resolve("server.tomcat.remoteip.internal-proxies");

    private static String resolve(String key) {
        try {
            var sources = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
            var environment = new StandardEnvironment();
            sources.forEach(source -> environment.getPropertySources().addLast(source));
            return environment.getRequiredProperty(key);
        } catch (Exception ex) {
            throw new IllegalStateException("application.yml did not resolve " + key, ex);
        }
    }

    /** Tomcat's valve, not Spring's filter: only the valve walks the header from the far end. */
    @Test
    void theForwardedHeaderStrategyIsTheOneThatDiscardsKnownHops() {
        assertThat(resolve("server.forward-headers-strategy")).isEqualTo("native");
        assertThat(internalProxies).doesNotContain("$", "JANUS_TRUSTED_PROXIES");
    }

    @Test
    void aCallerCannotChooseTheAddressItIsThrottledOn() {
        // What nginx forwards when the caller sent an X-Forwarded-For of its own and the proxy
        // appended the real peer, which is what $proxy_add_x_forwarded_for does.
        assertThat(clientBehindProxy("172.18.0.4", "10.0.0.1, 203.0.113.10")).isEqualTo("203.0.113.10");
        // The same trick aimed at a private address, in case that reads as one more internal hop.
        assertThat(clientBehindProxy("172.18.0.4", "192.168.1.1, 203.0.113.10")).isEqualTo("203.0.113.10");
        // And a whole forged chain, which is what an attacker rotating the header actually sends.
        assertThat(clientBehindProxy("172.18.0.4", "8.8.8.8, 1.1.1.1, 203.0.113.10"))
                .isEqualTo("203.0.113.10");
    }

    @Test
    void anHonestChainStillNamesTheClient() {
        // Traefik in front of the web image: the caller, then internal hops.
        assertThat(clientBehindProxy("172.18.0.4", "203.0.113.10, 172.18.0.9")).isEqualTo("203.0.113.10");
        // One nginx, the shape of compose.prod.yml.
        assertThat(clientBehindProxy("172.18.0.4", "203.0.113.10")).isEqualTo("203.0.113.10");
        // No proxy at all, as in local development.
        assertThat(clientBehindProxy("203.0.113.10", null)).isEqualTo("203.0.113.10");
    }

    /** Carrier NAT is where real clients live, so it must not be mistaken for a hop we operate. */
    @Test
    void carrierNatIsNotTreatedAsAProxy() {
        assertThat(clientBehindProxy("172.18.0.4", "10.0.0.1, 100.64.7.9")).isEqualTo("100.64.7.9");
    }

    /** The address Janus counts a request against, given the peer and the header it arrived with. */
    private String clientBehindProxy(String peer, String forwardedFor) {
        var filter = new RemoteIpFilter();
        filter.setInternalProxies(internalProxies);
        filter.setRemoteIpHeader(resolve("server.tomcat.remoteip.remote-ip-header"));

        var request = new MockHttpServletRequest("GET", "/api/admin/providers");
        request.setRemoteAddr(peer);
        if (forwardedFor != null) request.addHeader("X-Forwarded-For", forwardedFor);

        var seen = new String[1];
        try {
            filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> seen[0] = req.getRemoteAddr());
        } catch (Exception ex) {
            throw new IllegalStateException("the configured internal-proxies value was not usable", ex);
        }
        return seen[0];
    }
}
