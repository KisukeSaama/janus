package io.janus.gateway;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.function.Predicate;

import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import io.janus.providers.DestinationValidator;

/**
 * The outbound HTTP clients used for proxied requests, kept separate from the client that talks to
 * OpenBao: these must refuse private addresses, and OpenBao is deliberately a private address.
 *
 * <p>There are two because the address check runs inside the connection pool, where nothing knows
 * which provider caused the connection. The rule therefore has to be fixed when the client is built,
 * and a destination registered as being on a local network needs a different rule from every other
 * one. Both share Reactor Netty's default connection pool, so the second client costs a
 * configuration object rather than a second set of sockets.
 */
@Configuration
public class GatewayHttpClientConfig {

    /** Raised when a hostname resolves to an address the gateway is not allowed to reach. */
    public static class BlockedDestinationException extends RuntimeException {
        public BlockedDestinationException(String message) {
            super(message);
        }
    }

    /** For every destination that has not been declared as being on a local network. */
    @Bean
    WebClient gatewayWebClient(
            DestinationValidator destinations,
            @Value("${janus.gateway.max-response-bytes:10485760}") int maxResponseBytes,
            @Value("${janus.gateway.connect-timeout-millis:5000}") int connectTimeoutMillis,
            @Value("${janus.gateway.response-timeout-seconds:30}") long responseTimeoutSeconds) {
        return client(
                address -> destinations.isDisallowed(address, false),
                maxResponseBytes,
                connectTimeoutMillis,
                responseTimeoutSeconds);
    }

    /**
     * For destinations declared as being on a local network. It admits the site-local, carrier-grade
     * NAT, and unique-local ranges — loopback and link-local remain refused here exactly as above,
     * because the difference between the two clients is which network a destination lives on, not
     * whether the check applies.
     */
    @Bean
    WebClient gatewayPrivateWebClient(
            DestinationValidator destinations,
            @Value("${janus.gateway.max-response-bytes:10485760}") int maxResponseBytes,
            @Value("${janus.gateway.connect-timeout-millis:5000}") int connectTimeoutMillis,
            @Value("${janus.gateway.response-timeout-seconds:30}") long responseTimeoutSeconds) {
        return client(
                address -> destinations.isDisallowed(address, true),
                maxResponseBytes,
                connectTimeoutMillis,
                responseTimeoutSeconds);
    }

    private WebClient client(
            Predicate<java.net.InetAddress> blocked,
            int maxResponseBytes,
            int connectTimeoutMillis,
            long responseTimeoutSeconds) {
        HttpClient httpClient = HttpClient.create()
                // Redirects are a classic way to walk a proxy out of its allowlist: the caller is
                // authorised for one route, and the upstream answers 302 to another host entirely.
                .followRedirect(false)
                .compress(false)
                .responseTimeout(Duration.ofSeconds(responseTimeoutSeconds))
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMillis)
                // Checking the address that is actually about to be connected to closes the DNS
                // rebinding window that a check performed only at registration time leaves open.
                .doAfterResolve((connection, address) -> {
                    if (address instanceof InetSocketAddress socketAddress
                            && socketAddress.getAddress() != null
                            && blocked.test(socketAddress.getAddress()))
                        throw new BlockedDestinationException("Destination resolves to a private or local address");
                });

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(maxResponseBytes))
                .build();
    }
}
