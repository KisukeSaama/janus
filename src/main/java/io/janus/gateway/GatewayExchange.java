package io.janus.gateway;

import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

import io.janus.credentials.Identity;
import io.janus.gateway.graphql.GraphQlCall;
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
 * @param graphql    what the call asks a GraphQL endpoint for, or null when it is not a GraphQL
 *                   operation; when present it, rather than the method, says whether the call reads
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
        Identity pinned,
        GraphQlCall graphql) {

    /** Methods whose answer may be reused. */
    private static final Set<HttpMethod> SAFE = Set.of(HttpMethod.GET, HttpMethod.HEAD);
    /** Methods a second attempt cannot duplicate the effect of. */
    private static final Set<HttpMethod> IDEMPOTENT =
            Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.PUT, HttpMethod.DELETE);

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
        this(provider, grant, applicationId, method, route, headers, body, correlationId, null, null);
    }

    /** For the callers written before a call could be a GraphQL operation. */
    public GatewayExchange(
            Provider provider,
            Grant grant,
            UUID applicationId,
            HttpMethod method,
            GatewayPath route,
            HttpHeaders headers,
            byte[] body,
            String correlationId,
            Identity pinned) {
        this(provider, grant, applicationId, method, route, headers, body, correlationId, pinned, null);
    }

    /**
     * Whether the call only reads, which is what licenses reusing an answer and sharing one between
     * identical calls. A GraphQL query reads however it travels; anything else is judged by its
     * method, as HTTP defines it.
     */
    public boolean reads() {
        return graphql != null ? graphql.readsOnly() : SAFE.contains(method);
    }

    /** Whether sending the call twice has the effect of sending it once. */
    public boolean idempotent() {
        return graphql != null ? graphql.readsOnly() : IDEMPOTENT.contains(method);
    }

    /** Whether a successful answer means state upstream may have changed. */
    public boolean writes() {
        return graphql != null ? graphql.writes() : !SAFE.contains(method);
    }

    /**
     * What identifies the endpoint for {@link IdentityMemory}, beyond its path: for a GraphQL call,
     * the shape of the operation, since every operation shares one path. Null for everything else.
     */
    public String operationShape() {
        return graphql == null ? null : graphql.shape();
    }
}
