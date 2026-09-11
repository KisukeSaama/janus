package io.janus.gateway;

import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;
import org.springframework.web.socket.server.support.WebSocketHandlerMapping;
import org.springframework.web.socket.server.support.WebSocketHttpRequestHandler;

/**
 * Where a WebSocket handshake to the gateway is answered.
 *
 * <p>The same addresses as the proxy, {@code /gateway/**}, and ahead of it, but only for a request
 * that asks to upgrade: everything else falls through to {@link GatewayController} as before. The
 * handshake is authenticated by the gateway's filter chain first, like any request to those addresses.
 *
 * <p>Only the two GraphQL subprotocols are accepted. A caller's handshake may list others (a browser
 * carrying its bearer token as one, see {@code ApiKeyAuthenticationFilter}), and none of those is
 * ever echoed back as the protocol in use.
 */
@Configuration
public class GraphQlSocketConfig {

    @Bean
    WebSocketHandlerMapping graphQlSocketMapping(GraphQlSocketHandler handler, GraphQlSocketHandshake handshake) {
        var negotiation = new DefaultHandshakeHandler();
        negotiation.setSupportedProtocols(GraphQlSocketHandler.PROTOCOLS.toArray(String[]::new));
        var endpoint = new WebSocketHttpRequestHandler(handler, negotiation);
        endpoint.setHandshakeInterceptors(List.of(handshake));

        var mapping = new WebSocketHandlerMapping();
        mapping.setUrlMap(Map.of("/gateway/**", endpoint));
        mapping.setWebSocketUpgradeMatch(true);
        mapping.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return mapping;
    }
}
