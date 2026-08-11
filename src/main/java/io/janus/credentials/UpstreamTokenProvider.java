package io.janus.credentials;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

import org.slf4j.*;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.*;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns {@code client_id:client_secret} into a bearer token, and holds it so it is asked for once.
 *
 * <p>The exchange deliberately does not go through {@code GatewayTrafficService}: it is a control
 * call, not a proxied one. It must not consume a caller's allowance, must not be answered from the
 * response cache, and must not be retried by a policy meant for somebody else's traffic.
 *
 * <p>It does use the gateway's HTTP client, because a token endpoint is a public destination —
 * exactly what that client is configured for: no redirect following (a 302 away from a token
 * endpoint is how a secret is stolen), the resolved address checked against the SSRF rules, bounded
 * timeouts and response size.
 *
 * <p>Concurrent misses are coalesced on the same pattern the proxy uses for identical reads: the
 * first caller performs the exchange and the others wait for its answer, so a cold token costs one
 * call rather than one per in-flight request.
 */
@Service
public class UpstreamTokenProvider {
    private static final Logger log = LoggerFactory.getLogger(UpstreamTokenProvider.class);

    private final WebClient web;
    private final UpstreamTokenCache cache;
    private final ObjectMapper mapper;
    private final ConcurrentMap<UUID, CompletableFuture<String>> inFlight = new ConcurrentHashMap<>();

    public UpstreamTokenProvider(WebClient gatewayWebClient, UpstreamTokenCache cache, ObjectMapper mapper) {
        this.web = gatewayWebClient;
        this.cache = cache;
        this.mapper = mapper;
    }

    /**
     * The bearer token to present for this credential.
     *
     * @param storedSecret the {@code client_id:client_secret} pair read from OpenBao
     * @throws TokenExchangeException when the provider refuses; never carrying its response body
     */
    public String tokenFor(Credential credential, String storedSecret) {
        var held = cache.lookup(credential.getId());
        if (held.isPresent()) return held.get();

        var recent = cache.recentFailure(credential.getId());
        if (recent.isPresent())
            throw new TokenExchangeException(
                    "The token endpoint refused these credentials moments ago (status " + recent.getAsInt() + ")");

        return coalesced(credential, storedSecret);
    }

    /** One exchange for however many identical requests arrive while it is running. */
    private String coalesced(Credential credential, String storedSecret) {
        var mine = new CompletableFuture<String>();
        var leader = inFlight.putIfAbsent(credential.getId(), mine);
        if (leader != null) {
            try {
                return leader.get(30, TimeUnit.SECONDS);
            } catch (ExecutionException ex) {
                throw ex.getCause() instanceof RuntimeException runtime
                        ? runtime
                        : new TokenExchangeException("The token exchange failed");
            } catch (TimeoutException ex) {
                throw new TokenExchangeException("The token endpoint did not answer in time");
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new TokenExchangeException("Interrupted while waiting for a token");
            }
        }
        try {
            String token = exchange(credential, storedSecret);
            mine.complete(token);
            return token;
        } catch (RuntimeException ex) {
            mine.completeExceptionally(ex);
            throw ex;
        } finally {
            inFlight.remove(credential.getId(), mine);
        }
    }

    private String exchange(Credential credential, String storedSecret) {
        int separator = storedSecret.indexOf(':');
        if (separator < 0)
            throw new TokenExchangeException("The stored secret is not in the form client_id:client_secret");
        String clientId = storedSecret.substring(0, separator);
        String clientSecret = storedSecret.substring(separator + 1);

        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        if (credential.getTokenScopes() != null) form.add("scope", credential.getTokenScopes());
        if (credential.getTokenClientAuth() == TokenClientAuth.POST) {
            form.add("client_id", clientId);
            form.add("client_secret", clientSecret);
        }

        ResponseEntity<byte[]> response;
        try {
            response = web.post()
                    .uri(credential.getTokenUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .headers(headers -> {
                        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
                        if (credential.getTokenClientAuth() != TokenClientAuth.POST)
                            headers.setBasicAuth(Base64.getEncoder()
                                    .encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8)));
                    })
                    .body(BodyInserters.fromFormData(form))
                    .exchangeToMono(result -> result.toEntity(byte[].class))
                    .block();
        } catch (WebClientResponseException ex) {
            // The message of this exception embeds the response body, and a token endpoint that
            // refuses credentials often quotes them back. Only the status is ever surfaced.
            return refuse(credential, ex.getStatusCode().value());
        } catch (RuntimeException ex) {
            log.warn("Could not reach the token endpoint for credential {}", credential.getId());
            throw new TokenExchangeException("The token endpoint could not be reached");
        }

        if (response == null || !response.getStatusCode().is2xxSuccessful())
            return refuse(
                    credential, response == null ? 0 : response.getStatusCode().value());

        return read(credential, response.getBody());
    }

    /** Remembers the refusal for a moment, so the next request does not repeat it. */
    private String refuse(Credential credential, int status) {
        cache.storeFailure(credential.getId(), status);
        log.warn("The token endpoint refused credential {} with status {}", credential.getId(), status);
        throw new TokenExchangeException("The token endpoint refused these credentials (status " + status + ")");
    }

    private String read(Credential credential, byte[] body) {
        if (body == null || body.length == 0) return refuse(credential, 200);
        try {
            var json = mapper.readTree(body);
            String token = json.path("access_token").asString(null);
            if (token == null || token.isBlank()) return refuse(credential, 200);

            var expires = json.path("expires_in");
            cache.store(credential.getId(), token, expires.isNumber() ? expires.asLong() : null);
            return token;
        } catch (RuntimeException ex) {
            // Whatever came back was not the answer RFC 6749 describes. Saying so is all that is safe.
            return refuse(credential, 200);
        }
    }
}
