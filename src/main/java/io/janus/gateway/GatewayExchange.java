package io.janus.gateway;

import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

import io.janus.credentials.Identity;
import io.janus.grants.Grant;
import io.janus.providers.Provider;

/**
 * One authorised call, handed to the outbound half of the gateway.
 *
 * <p>Nothing here is caller-supplied except the path, the body, and the identity: the provider and
 * the grant were both resolved from the registry, and the headers have already been through
 * {@link HeaderPolicy}. The credential is named but not read — the secret is fetched only if a
 * request actually has to leave this process.
 *
 * @param headers    request headers as they will be forwarded, before the credential is injected
 * @param pinned     the identity the caller asked for by name, or null when it left the choice open,
 *                   which is the ordinary case and the one the gateway answers from what it has
 *                   learned
 */
public record GatewayExchange(
        Provider provider,
        Grant grant,
        UUID applicationId,
        HttpMethod method,
        GatewayPath route,
        HttpHeaders headers,
        byte[] body,
        String correlationId,
        Identity pinned) {

    /** For the callers that never state an identity, which is every one written before there were two. */
    public GatewayExchange(
            Provider provider,
            Grant grant,
            UUID applicationId,
            HttpMethod method,
            GatewayPath route,
            HttpHeaders headers,
            byte[] body,
            String correlationId) {
        this(provider, grant, applicationId, method, route, headers, body, correlationId, null);
    }
}
