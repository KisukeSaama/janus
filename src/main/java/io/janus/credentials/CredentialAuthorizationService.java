package io.janus.credentials;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;

import io.janus.accounts.AccessScope;
import io.janus.audit.AuditAction;
import io.janus.audit.AuditService;
import io.janus.gateway.TrafficPolicyRegistry;
import io.janus.openbao.OpenBaoClient;
import io.janus.shared.NotFoundException;

/**
 * The half of an authorisation-code connection that a person has to be present for.
 *
 * <p>Everything else in Janus can be arranged by an administrator filling in a form. This cannot: the
 * provider will not issue a token for somebody's playlists until that somebody has said so, at the
 * provider's own site, in their own browser. Janus's part is to hold the whole exchange around that
 * one moment — the redirect out, the state, the PKCE verifier, the code coming back, the swap for a
 * refresh token — so a client service never sees any of it, and so an operator sees a button rather
 * than a protocol.
 *
 * <p>The refresh token that results is written to OpenBao beside the client secret and never returned
 * by this API. What the console learns is that consent exists, when it was given, and whom the
 * provider says it belongs to.
 */
@Service
public class CredentialAuthorizationService {
    private static final Logger log = LoggerFactory.getLogger(CredentialAuthorizationService.class);

    /** Where a provider sends the browser back to. One path, for every destination Janus knows. */
    public static final String CALLBACK_PATH = "/oauth/callback";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    private final CredentialRepository credentials;
    private final AuthorizationStateRepository states;
    private final AuthorizationStateConsumer consumer;
    private final OpenBaoClient bao;
    private final UpstreamTokenCache tokens;
    private final WebClient web;
    private final ObjectMapper mapper;
    private final AccessScope scope;
    private final AuditService audit;
    private final TrafficPolicyRegistry traffic;
    private final String publicUrl;

    public CredentialAuthorizationService(
            CredentialRepository credentials,
            AuthorizationStateRepository states,
            AuthorizationStateConsumer consumer,
            OpenBaoClient bao,
            UpstreamTokenCache tokens,
            WebClient gatewayWebClient,
            ObjectMapper mapper,
            AccessScope scope,
            AuditService audit,
            TrafficPolicyRegistry traffic,
            @Value("${janus.public-url:http://localhost:8080}") String publicUrl) {
        this.credentials = credentials;
        this.states = states;
        this.consumer = consumer;
        this.bao = bao;
        this.tokens = tokens;
        this.web = gatewayWebClient;
        this.mapper = mapper;
        this.scope = scope;
        this.audit = audit;
        this.traffic = traffic;
        this.publicUrl = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
        // Said once, at startup, because the symptom otherwise appears much later and elsewhere: a
        // provider refusing an authorisation for a redirect nobody registered, with no indication
        // that the address Janus sent was the built-in default rather than this deployment's.
        if (this.publicUrl.contains("localhost") || this.publicUrl.contains("127.0.0.1"))
            log.warn(
                    "janus.public-url is still {}, so connected accounts will send people back to {} — set it to this "
                            + "deployment's public address before authorising any connection",
                    this.publicUrl,
                    this.publicUrl + CALLBACK_PATH);
    }

    /** Where to send somebody, and what it is they are about to agree to. */
    public record Started(String authorizationUrl, String providerName) {}

    /** What came back, named so the console can say which connection is now live. */
    public record Completed(UUID credentialId, String providerName, String subject) {}

    /**
     * Begins an authorisation and returns the address the browser must be sent to.
     *
     * <p>Nothing is granted here. This writes down what will be needed when the browser returns and
     * builds a URL at the provider; a person may equally close the tab, in which case the row expires
     * and is swept.
     */
    @Transactional
    public Started start(UUID credentialId) {
        var credential = credentials
                .findOwnedBy(credentialId, scope.ownerFilter())
                .orElseThrow(() -> new NotFoundException("Credential not found"));
        if (!credential.offersConnection())
            throw new IllegalArgumentException("This API does not offer an account connection");
        if (!credential.connectionUsable())
            throw new IllegalArgumentException(
                    "This connection has no OAuth client yet. Supply its client id and secret first.");

        // The client id is half of the stored pair. Reading it here rather than storing it separately
        // keeps one copy of the client's identity, in the one place that is meant to hold it.
        String clientId = clientId(credential);

        String state = randomToken(32);
        String verifier = randomToken(64);
        String redirect = publicUrl + CALLBACK_PATH;

        states.save(new AuthorizationState(state, credential.getId(), scope.accountId(), verifier, redirect));

        var url = UriComponentsBuilder.fromUriString(credential.getConnectionAuthorizationUrl())
                .queryParam("response_type", "code")
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", redirect)
                .queryParam("state", state)
                // PKCE is not optional here even though a confidential client could do without it:
                // it costs one hash and closes the window where an intercepted code is worth having.
                .queryParam("code_challenge", challenge(verifier))
                .queryParam("code_challenge_method", "S256");
        if (credential.getConnectionScopes() != null) url.queryParam("scope", credential.getConnectionScopes());

        audit.recordAdmin(
                AuditAction.CREDENTIAL_AUTHORIZATION_STARTED,
                credential.getProvider().getId(),
                credential.getName());
        return new Started(url.build().toUriString(), credential.getProvider().getName());
    }

    /**
     * Completes an authorisation from what the provider sent the browser back with.
     *
     * <p>Deliberately not scoped to the signed-in account: a browser returning from another site does
     * not carry the console's session cookie, so the state is what this is trusted on. It is random,
     * single-use, and short-lived, and the account it belongs to was written down when it was issued.
     */
    @Transactional
    public Completed complete(String state, String code) {
        var pending = states.findById(state)
                .orElseThrow(() -> new IllegalArgumentException(
                        "This authorisation is no longer valid. Start it again from the console."));
        // Single use, whatever happens next: a code that fails to exchange must not be retryable
        // against the same state, and a state that succeeded must not be replayable at all. In its
        // own transaction, because every failure below is reported by throwing and a rollback here
        // would hand the state back — see AuthorizationStateConsumer.
        if (!consumer.consume(state))
            throw new IllegalArgumentException(
                    "This authorisation is no longer valid. Start it again from the console.");
        if (pending.expired())
            throw new IllegalArgumentException("This authorisation took too long. Start it again from the console.");

        var credential = credentials
                .findById(pending.getCredentialId())
                .orElseThrow(() -> new NotFoundException("Credential not found"));

        var response = redeem(credential, code, pending);
        String refresh = response.path("refresh_token").asString(null);
        if (refresh == null || refresh.isBlank())
            // Google wants access_type=offline, others want prompt=consent, and several simply do not
            // issue one on a second authorisation. Without it Janus cannot keep the connection alive
            // past the first hour, which is not a connection worth recording as one.
            throw new IllegalArgumentException("The provider did not return a refresh token, so this connection "
                    + "could not be kept alive. Check that offline access is requested in the scopes.");

        bao.write(credential.refreshTokenPath(), refresh);

        String subject = subjectOf(response);
        credential.authorized(subject);
        // Endpoints that refused the application's identity while nobody was connected were learned
        // when nothing could be done about them. Something can be now, so what was learned goes —
        // along with the stored responses, which were fetched as somebody else.
        //
        // Before the token below is kept, not after: this clears held tokens too, and clearing one
        // that was stored a line earlier would throw away the round trip it exists to save.
        traffic.forgetCredential(credential.getId());

        // The access token is good now, so it is kept: a person who has just consented should not wait
        // for a refresh round trip on their first call.
        String access = response.path("access_token").asString(null);
        if (access != null && !access.isBlank()) {
            var expires = response.path("expires_in");
            tokens.store(credential.getId(), Identity.ACCOUNT, access, expires.isNumber() ? expires.asLong() : null);
        }

        audit.recordAdmin(
                AuditAction.CREDENTIAL_AUTHORIZED,
                credential.getProvider().getId(),
                subject == null ? credential.getName() : credential.getName() + " (" + subject + ")");
        return new Completed(credential.getId(), credential.getProvider().getName(), subject);
    }

    /**
     * Forgets a stored consent, so the connection stops working until somebody agrees again.
     *
     * <p>The refresh token is deleted rather than kept for later: what makes this action meaningful is
     * that Janus can no longer act for the person afterwards. Revoking it at the provider is theirs to
     * do, from their own account's settings, and no API here can do it on their behalf.
     */
    @Transactional
    public void revoke(UUID credentialId) {
        var credential = credentials
                .findOwnedBy(credentialId, scope.ownerFilter())
                .orElseThrow(() -> new NotFoundException("Credential not found"));
        if (!credential.offersConnection())
            throw new IllegalArgumentException("This connection carries no authorisation");

        credential.forgetAuthorization();
        // What an endpoint answered while somebody was connected says nothing about what it answers
        // now. Left in place, every route learned as the account's would keep being sent there, and
        // every response fetched as them would still be served.
        traffic.forgetCredential(credential.getId());
        try {
            bao.delete(credential.refreshTokenPath());
        } catch (RuntimeException ex) {
            // The record already says nobody has agreed, which is what every later decision reads.
            // A refresh token nothing will ever look up again is worth a line in the log, not a 500.
            log.warn("Could not delete the refresh token for credential {}", credential.getId());
        }
        audit.recordAdmin(
                AuditAction.CREDENTIAL_AUTHORIZATION_REVOKED,
                credential.getProvider().getId(),
                credential.getName());
    }

    /** Swaps the code for tokens. Failures never carry the provider's body, which quotes what it was sent. */
    private tools.jackson.databind.JsonNode redeem(Credential credential, String code, AuthorizationState pending) {
        String stored = bao.read(credential.connectionSecretPath());
        int separator = stored.indexOf(':');
        if (separator < 0)
            throw new IllegalArgumentException("The stored secret is not in the form client_id:client_secret");
        String clientId = stored.substring(0, separator);
        String clientSecret = stored.substring(separator + 1);

        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", pending.getRedirectUri());
        form.add("code_verifier", pending.getCodeVerifier());
        if (credential.getConnectionClientAuth() == TokenClientAuth.POST) {
            form.add("client_id", clientId);
            form.add("client_secret", clientSecret);
        }

        byte[] body;
        try {
            body = web.post()
                    .uri(credential.getConnectionTokenUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .headers(headers -> {
                        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
                        if (credential.getConnectionClientAuth() != TokenClientAuth.POST)
                            headers.setBasicAuth(TokenClientAuth.basicCredentials(clientId, clientSecret));
                    })
                    .body(BodyInserters.fromFormData(form))
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .block();
        } catch (RuntimeException ex) {
            log.warn("The token endpoint refused an authorisation for credential {}", credential.getId());
            throw new IllegalArgumentException("The provider refused this authorisation. Check the client id and "
                    + "secret, and that the redirect below is registered with them.");
        }
        if (body == null || body.length == 0)
            throw new IllegalArgumentException("The provider returned nothing in exchange for the authorisation");
        try {
            return mapper.readTree(body);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("The provider's answer was not the one RFC 6749 describes");
        }
    }

    /**
     * Whom the consent belongs to, when the provider happened to say.
     *
     * <p>Read from the {@code id_token} without verifying its signature, which would be unsound for an
     * authentication decision and is sound here: it arrived over TLS from the token endpoint itself,
     * and the value is only ever displayed. Nothing is decided on it.
     */
    private String subjectOf(tools.jackson.databind.JsonNode response) {
        String idToken = response.path("id_token").asString(null);
        if (idToken == null) return null;
        var parts = idToken.split("\\.");
        if (parts.length < 2) return null;
        try {
            var claims = mapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
            String email = claims.path("email").asString(null);
            return email != null ? email : claims.path("sub").asString(null);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private String clientId(Credential credential) {
        String stored;
        try {
            stored = bao.read(credential.connectionSecretPath());
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("This connection has no client id and secret stored yet");
        }
        int separator = stored.indexOf(':');
        if (separator < 0)
            throw new IllegalArgumentException("The stored secret is not in the form client_id:client_secret");
        return stored.substring(0, separator);
    }

    private static String randomToken(int bytes) {
        var value = new byte[bytes];
        RANDOM.nextBytes(value);
        return URL.encodeToString(value);
    }

    /** RFC 7636 §4.2: the challenge is the base64url of the verifier's SHA-256, ASCII in. */
    private static String challenge(String verifier) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return URL.encodeToString(digest.digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required of every JVM", ex);
        }
    }

    /**
     * Drops the authorisations nobody came back from.
     *
     * <p>Most of them: opening a consent screen and closing the tab is the ordinary way one of these
     * ends. Sweeping is what keeps a table of short-lived secrets from quietly becoming a long-lived
     * one, and it runs on a fixed delay rather than a cron because nothing about it is tied to a
     * time of day.
     */
    @Scheduled(fixedDelayString = "${janus.authorization-sweep-millis:900000}")
    @Transactional
    public void sweepExpired() {
        int dropped = states.deleteExpired(java.time.Instant.now());
        if (dropped > 0) log.debug("Swept {} authorisations that were never completed", dropped);
    }

    /** The address a provider must be told to send people back to, shown so it can be registered there. */
    public URI callbackUri() {
        return URI.create(publicUrl + CALLBACK_PATH);
    }
}
