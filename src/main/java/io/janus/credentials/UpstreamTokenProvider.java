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

import io.janus.openbao.OpenBaoClient;

/**
 * Turns whatever a credential stores into a bearer token, and holds it so it is asked for once.
 *
 * <p>Two strategies end here, and they differ only in what they present to get the token: a client
 * secret, for an application speaking as itself, or a refresh token, for an application speaking for
 * a person who agreed to it.
 *
 * <p>What comes back is the same in both cases, which is the point: a client service asks Janus for a
 * resource and never learns which of these was involved, nor when the token behind it changed.
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
    private final OpenBaoClient bao;
    private final ConcurrentMap<Pending, CompletableFuture<String>> inFlight = new ConcurrentHashMap<>();

    public UpstreamTokenProvider(
            WebClient gatewayWebClient, UpstreamTokenCache cache, ObjectMapper mapper, OpenBaoClient bao) {
        this.web = gatewayWebClient;
        this.cache = cache;
        this.mapper = mapper;
        this.bao = bao;
    }

    /**
     * The bearer token to present for this credential, speaking for whom the caller asked.
     *
     * @param identity     which of the credential's two tokens is wanted
     * @param storedSecret the {@code client_id:client_secret} pair read from OpenBao
     * @throws TokenExchangeException when the provider refuses; never carrying its response body
     */
    public String tokenFor(Credential credential, Identity identity, String storedSecret) {
        var held = cache.lookup(credential.getId(), identity);
        if (held.isPresent()) return held.get();

        // Nobody has agreed yet, so there is nothing to refresh. Said plainly, because the fix is an
        // action in the console rather than anything about this request.
        if (identity == Identity.ACCOUNT && credential.awaitingAuthorization())
            throw new TokenExchangeException("This connection has not been authorised yet");

        var recent = cache.recentFailure(credential.getId(), identity);
        if (recent.isPresent())
            throw new TokenExchangeException(
                    "The token endpoint refused these credentials moments ago (status " + recent.getAsInt() + ")");

        return coalesced(credential, identity, storedSecret);
    }

    /**
     * Drops the held token for one identity, so the next call obtains a fresh one.
     *
     * <p>Called when an upstream refuses a request made with it. A refusal is ambiguous — the token
     * may have aged out, or it may be the wrong identity for that endpoint — and this is how the
     * gateway rules out the first before concluding the second.
     */
    public void invalidate(UUID credentialId, Identity identity) {
        cache.invalidate(credentialId, identity);
    }

    /** One exchange for however many identical requests arrive while it is running. */
    private String coalesced(Credential credential, Identity identity, String storedSecret) {
        var mine = new CompletableFuture<String>();
        var leader = inFlight.putIfAbsent(new Pending(credential.getId(), identity), mine);
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
            String token = exchange(credential, identity, storedSecret);
            mine.complete(token);
            return token;
        } catch (RuntimeException ex) {
            mine.completeExceptionally(ex);
            throw ex;
        } finally {
            inFlight.remove(new Pending(credential.getId(), identity), mine);
        }
    }

    /** One exchange in flight, told apart by whom it is for: the two are different requests. */
    private record Pending(UUID credentialId, Identity identity) {}

    private String exchange(Credential credential, Identity identity, String storedSecret) {
        var form = new LinkedMultiValueMap<String, String>();
        String[] client = split(storedSecret);

        // Which endpoint, and how Janus proves who it is there, follow from whom the token is for:
        // the application's exchange is the one its auth type describes, the account's is the one its
        // connection describes. They are usually the same endpoint, and need not be.
        String tokenUrl;
        TokenClientAuth clientAuth;
        if (identity == Identity.ACCOUNT) {
            if (!credential.offersConnection())
                throw new IllegalStateException("This destination offers no account connection");
            tokenUrl = credential.getConnectionTokenUrl();
            clientAuth = credential.getConnectionClientAuth();
            form.add("grant_type", "refresh_token");
            form.add("refresh_token", refreshToken(credential));
            // No scope: a refresh may narrow what was granted but never widen it, and providers
            // differ on whether restating it is accepted or refused.
        } else {
            if (!credential.getAuthType().exchanged())
                throw new IllegalStateException(credential.getAuthType() + " does not exchange anything for a token");
            tokenUrl = credential.getTokenUrl();
            clientAuth = credential.getTokenClientAuth();
            form.add("grant_type", "client_credentials");
            if (credential.getTokenScopes() != null) form.add("scope", credential.getTokenScopes());
        }

        if (clientAuth == TokenClientAuth.POST) {
            form.add("client_id", client[0]);
            form.add("client_secret", client[1]);
        }

        ResponseEntity<byte[]> response;
        try {
            response = web.post()
                    .uri(tokenUrl)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .headers(headers -> {
                        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
                        if (clientAuth != TokenClientAuth.POST)
                            headers.setBasicAuth(Base64.getEncoder()
                                    .encodeToString((client[0] + ":" + client[1]).getBytes(StandardCharsets.UTF_8)));
                    })
                    .body(BodyInserters.fromFormData(form))
                    .exchangeToMono(result -> result.toEntity(byte[].class))
                    .block();
        } catch (WebClientResponseException ex) {
            // The message of this exception embeds the response body, and a token endpoint that
            // refuses credentials often quotes them back. Only the status is ever surfaced.
            return refuse(credential, identity, ex.getStatusCode().value());
        } catch (RuntimeException ex) {
            log.warn("Could not reach the token endpoint for credential {}", credential.getId());
            throw new TokenExchangeException("The token endpoint could not be reached");
        }

        if (response == null || !response.getStatusCode().is2xxSuccessful())
            return refuse(
                    credential, identity, response == null ? 0 : response.getStatusCode().value());

        return read(credential, identity, response.getBody());
    }

    /** The pair a credential stores as one string, refused rather than guessed at when it is not one. */
    private static String[] split(String storedSecret) {
        int separator = storedSecret == null ? -1 : storedSecret.indexOf(':');
        if (separator < 0)
            throw new TokenExchangeException("The stored secret is not in the form client_id:client_secret");
        return new String[] {storedSecret.substring(0, separator), storedSecret.substring(separator + 1)};
    }

    /**
     * The refresh token a person's consent produced, held beside the client secret rather than inside
     * it: the two arrive from different people at different times, and the provider may replace this
     * one on every use while the other stays as it was.
     */
    private String refreshToken(Credential credential) {
        try {
            return bao.read(credential.refreshTokenPath());
        } catch (RuntimeException ex) {
            // Either nobody agreed, or what they agreed to was revoked and swept. Both are fixed the
            // same way, and neither is worth an upstream call to confirm.
            throw new TokenExchangeException("This connection needs to be authorised again");
        }
    }

    /** Remembers the refusal for a moment, so the next request does not repeat it. */
    private String refuse(Credential credential, Identity identity, int status) {
        cache.storeFailure(credential.getId(), identity, status);
        log.warn("The token endpoint refused credential {} with status {}", credential.getId(), status);
        throw new TokenExchangeException("The token endpoint refused these credentials (status " + status + ")");
    }

    private String read(Credential credential, Identity identity, byte[] body) {
        if (body == null || body.length == 0) return refuse(credential, identity, 200);
        try {
            var json = mapper.readTree(body);
            String token = json.path("access_token").asString(null);
            if (token == null || token.isBlank()) return refuse(credential, identity, 200);

            // Providers that rotate refresh tokens hand back a new one here, and the old one stops
            // working the moment it is used. Storing it before returning the access token is what
            // keeps a rotation from ending the connection at the next renewal.
            String rotated = json.path("refresh_token").asString(null);
            if (rotated != null && !rotated.isBlank() && identity == Identity.ACCOUNT) store(credential, rotated);

            var expires = json.path("expires_in");
            cache.store(credential.getId(), identity, token, expires.isNumber() ? expires.asLong() : null);
            return token;
        } catch (TokenExchangeException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            // Whatever came back was not the answer RFC 6749 describes. Saying so is all that is safe.
            return refuse(credential, identity, 200);
        }
    }

    private void store(Credential credential, String rotated) {
        try {
            bao.write(credential.refreshTokenPath(), rotated);
        } catch (RuntimeException ex) {
            // The access token in hand is still good, so this request will succeed; the next renewal
            // is the one that will fail, and it will say so plainly. Losing the request over a store
            // that may well succeed on the next pass would help nobody.
            log.error("Could not store the rotated refresh token for credential {}", credential.getId());
        }
    }
}
