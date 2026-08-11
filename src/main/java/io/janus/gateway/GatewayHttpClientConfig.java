package io.janus.gateway;

import java.net.InetSocketAddress;
import java.time.Duration;

import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import io.janus.providers.DestinationValidator;

/**
 * The outbound HTTP client used for proxied requests, kept separate from the client that talks to
 * OpenBao: this one must refuse private addresses, and OpenBao is deliberately a private address.
 */
@Configuration
public class GatewayHttpClientConfig {

    /** Raised when a hostname resolves to an address the gateway is not allowed to reach. */
    public static class BlockedDestinationException extends RuntimeException {
        public BlockedDestinationException(String message) {
            super(message);
        }
    }

    @Bean
    WebClient gatewayWebClient(
            DestinationValidator destinations,
            @Value("${janus.gateway.max-response-bytes:10485760}") int maxResponseBytes,
            @Value("${janus.gateway.connect-timeout-millis:5000}") int connectTimeoutMillis,
            @Value("${janus.gateway.response-timeout-seconds:30}") long responseTimeoutSeconds) {
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
                            && destinations.isDisallowed(socketAddress.getAddress()))
                        throw new BlockedDestinationException("Destination resolves to a private or local address");
                });

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(maxResponseBytes))
                .build();
    }
}
