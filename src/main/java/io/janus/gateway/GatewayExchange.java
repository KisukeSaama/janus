package io.janus.gateway;

import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

import io.janus.grants.Grant;
import io.janus.providers.Provider;

/**
 * One authorised call, handed to the outbound half of the gateway.
 *
 * <p>Nothing here is caller-supplied except the path and the body: the provider and the grant were
 * both resolved from the registry, and the headers have already been through {@link HeaderPolicy}.
 * The credential is named but not read — the secret is fetched only if a request actually has to
 * leave this process.
 *
 * @param headers request headers as they will be forwarded, before the credential is injected
 */
public record GatewayExchange(
        Provider provider,
        Grant grant,
        UUID applicationId,
        HttpMethod method,
        GatewayPath route,
        HttpHeaders headers,
        byte[] body,
        String correlationId) {}
