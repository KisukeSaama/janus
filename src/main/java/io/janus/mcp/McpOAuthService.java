package io.janus.mcp;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import io.janus.accounts.AccessScope;
import io.janus.accounts.AccountRepository;
import io.janus.accounts.ConsoleUser;
import io.janus.audit.AuditAction;
import io.janus.audit.AuditService;
import io.janus.oauth.OAuthException;
import io.janus.oauth.TokenResponse;
import io.janus.shared.NotFoundException;

/**
 * The authorisation server an MCP client meets before it may call {@code /mcp}.
 *
 * <p>The MCP specification settles every choice here, and each one is taken as written: OAuth 2.1,
 * authorisation code with PKCE (S256 only), public clients that register themselves (RFC 7591), and
 * tokens bound to this one resource (RFC 8707). What Janus adds is the part the specification leaves
 * to the server — who agrees, and where. That is a person signed in to the console, on a screen that
 * names the client and where the code will be sent, and the assistant then acts as that person, with
 * that person's role, and nothing more.
 *
 * <p>Tokens are opaque, 256 bits, and stored as SHA-256 like every other token Janus issues. The access
 * token is short-lived; the refresh token rotates on every use and, presented a second time, ends the
 * connection it belonged to — a value used twice is a value somebody else has.
 */
@Service
public class McpOAuthService {
    private static final Logger log = LoggerFactory.getLogger(McpOAuthService.class);

    public static final String SCOPE = "janus";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ACCESS_PREFIX = "jma_";
    private static final String REFRESH_PREFIX = "jmr_";
    private static final String CODE_PREFIX = "jmc_";
    private static final int MAX_REDIRECT_URIS = 5;
    private static final int MAX_REDIRECT_LENGTH = 500;
    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    /**
     * Schemes an address can carry that are not a place to send a code but an instruction to the
     * browser. A native app's private scheme — {@code cursor://}, {@code vscode://} — is legitimate
     * (RFC 8252 §7.1); these are not, whoever registers them.
     */
    private static final Set<String> FORBIDDEN_SCHEMES = Set.of("javascript", "data", "file", "vbscript", "blob");

    private final McpClientRepository clients;
    private final McpAuthorizationRepository authorizations;
    private final McpConnectionRepository connections;
    private final AccountRepository accounts;
    private final AccessScope scope;
    private final AuditService audit;
    private final String issuer;
    private final String consoleUrl;
    private final Duration accessTtl;
    private final Duration refreshTtl;

    public McpOAuthService(
            McpClientRepository clients,
            McpAuthorizationRepository authorizations,
            McpConnectionRepository connections,
            AccountRepository accounts,
            AccessScope scope,
            AuditService audit,
            @Value("${janus.public-url:http://localhost:8080}") String publicUrl,
            @Value("${janus.console-url:${janus.public-url:http://localhost:8080}}") String consoleUrl,
            @Value("${janus.mcp.access-token-ttl:1h}") Duration accessTtl,
            @Value("${janus.mcp.refresh-token-ttl:30d}") Duration refreshTtl) {
        this.clients = clients;
        this.authorizations = authorizations;
        this.connections = connections;
        this.accounts = accounts;
        this.scope = scope;
        this.audit = audit;
        this.issuer = trimSlash(publicUrl);
        this.consoleUrl = trimSlash(consoleUrl);
        this.accessTtl = accessTtl;
        this.refreshTtl = refreshTtl;
    }

    /** Where this deployment answers, which is also the authorisation server's identifier. */
    public String issuer() {
        return issuer;
    }

    /** The one resource these tokens are good for. */
    public String resource() {
        return issuer + "/mcp";
    }

    /* ── Registration (RFC 7591) ───────────────────────────────────────── */

    public record Registration(String clientId, String clientName, List<String> redirectUris, Instant issuedAt) {}

    /**
     * Registers a client. Open to anyone, as the protocol requires; what keeps that harmless is that a
     * registration grants nothing. It names where codes may be sent, and a code only exists once
     * somebody signed in has agreed on a screen showing that address.
     */
    @Transactional
    public Registration register(String clientName, List<String> redirectUris) {
        if (redirectUris == null || redirectUris.isEmpty())
            throw new OAuthException(
                    "invalid_redirect_uri", HttpStatus.BAD_REQUEST, "At least one redirect_uri is required");
        if (redirectUris.size() > MAX_REDIRECT_URIS)
            throw new OAuthException(
                    "invalid_redirect_uri",
                    HttpStatus.BAD_REQUEST,
                    "At most " + MAX_REDIRECT_URIS + " redirect_uris are accepted");
        for (String uri : redirectUris) checkRedirect(uri);
        String name = clientName == null || clientName.isBlank()
                ? "Unnamed MCP client"
                : clientName.strip().replaceAll("\\p{Cntrl}", "");
        if (name.length() > 120) name = name.substring(0, 120);
        var client = clients.save(new McpClient(name, List.copyOf(redirectUris)));
        return new Registration(client.getId().toString(), name, client.redirectUris(), client.getCreatedAt());
    }

    /**
     * Refuses an address a code must never be sent to: one with a fragment, one using a scheme that
     * is an instruction rather than a place, and plain HTTP anywhere but this machine (RFC 8252 §7.3).
     */
    static void checkRedirect(String value) {
        URI uri;
        try {
            if (value == null || value.isBlank() || value.length() > MAX_REDIRECT_LENGTH)
                throw new URISyntaxException(String.valueOf(value), "empty or too long");
            uri = new URI(value);
        } catch (URISyntaxException ex) {
            throw new OAuthException("invalid_redirect_uri", HttpStatus.BAD_REQUEST, "A redirect_uri is not a URI");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme.isEmpty() || FORBIDDEN_SCHEMES.contains(scheme) || uri.getFragment() != null)
            throw new OAuthException(
                    "invalid_redirect_uri", HttpStatus.BAD_REQUEST, "A redirect_uri cannot be used: " + value);
        if (scheme.equals("http") && !isLoopback(uri))
            throw new OAuthException(
                    "invalid_redirect_uri",
                    HttpStatus.BAD_REQUEST,
                    "Plain http is accepted only for a loopback redirect_uri: " + value);
        if ((scheme.equals("http") || scheme.equals("https")) && uri.getHost() == null)
            throw new OAuthException(
                    "invalid_redirect_uri", HttpStatus.BAD_REQUEST, "A redirect_uri needs a host: " + value);
    }

    static boolean isLoopback(URI uri) {
        return uri.getHost() != null && LOOPBACK.contains(uri.getHost().toLowerCase(Locale.ROOT));
    }

    /**
     * Whether a presented address is one the client registered. Exact, except that a loopback address
     * may name any port: a native client binds whichever one is free when it runs (RFC 8252 §7.3).
     */
    static boolean redirectMatches(McpClient client, String presented) {
        if (presented == null) return false;
        for (String registered : client.redirectUris()) {
            if (registered.equals(presented)) return true;
            try {
                var a = new URI(registered);
                var b = new URI(presented);
                if ("http".equalsIgnoreCase(a.getScheme())
                        && "http".equalsIgnoreCase(b.getScheme())
                        && isLoopback(a)
                        && a.getHost().equalsIgnoreCase(b.getHost())
                        && Objects.equals(a.getRawPath(), b.getRawPath())
                        && Objects.equals(a.getRawQuery(), b.getRawQuery())) return true;
            } catch (URISyntaxException ex) {
                // Not an address, so not a match.
            }
        }
        return false;
    }

    /* ── Authorisation ─────────────────────────────────────────────────── */

    /** What the client asked for at the authorisation endpoint, unread. */
    public record AuthorizeRequest(
            String responseType,
            String clientId,
            String redirectUri,
            String codeChallenge,
            String codeChallengeMethod,
            String state,
            String resource) {}

    /**
     * Records a request and answers where to send the browser: the console's consent screen, or —
     * once the client and its address are known to be genuine — back to the client with an error.
     *
     * <p>Until the redirect address is checked, nothing is sent to it (RFC 6749 §4.1.2.1): an attacker
     * naming somebody else's address would otherwise have Janus redirect a browser anywhere. Those
     * failures go to the console instead, which says what was wrong.
     */
    @Transactional
    public URI authorize(AuthorizeRequest request) {
        var client = findClient(request.clientId());
        if (client.isEmpty()) return console(Map.of("error", "invalid_client"));
        if (!redirectMatches(client.get(), request.redirectUri()))
            return console(Map.of("error", "invalid_redirect_uri"));

        String redirect = request.redirectUri();
        String state = request.state();
        if (!"code".equals(request.responseType()))
            return back(redirect, state, "unsupported_response_type", "Only response_type=code is offered");
        if (request.codeChallenge() == null
                || !request.codeChallenge().matches("[A-Za-z0-9_-]{43,128}")
                || !"S256".equals(request.codeChallengeMethod()))
            return back(redirect, state, "invalid_request", "PKCE with code_challenge_method=S256 is required");
        if (request.resource() != null && !sameResource(request.resource()))
            return back(redirect, state, "invalid_target", "This server issues tokens for " + resource() + " only");
        if (state != null && state.length() > 500)
            return back(redirect, state, "invalid_request", "The state is too long");

        var pending = authorizations.save(
                new McpAuthorization(random(32), client.get(), redirect, request.codeChallenge(), state));
        return console(Map.of("request", pending.getId()));
    }

    /**
     * A pending request as the consent screen shows it.
     *
     * @param loopback whether the code stays on this machine; when it does not, the screen says where
     *     it is going more loudly
     */
    public record Pending(
            String clientName, String redirectUri, String redirectHost, boolean loopback, Instant expiresAt) {}

    @Transactional(readOnly = true)
    public Pending describe(String requestId) {
        var pending = pending(requestId);
        var uri = URI.create(pending.getRedirectUri());
        String host = uri.getHost() != null ? uri.getHost() : uri.getScheme() + ":";
        return new Pending(
                pending.getClient().getClientName(),
                pending.getRedirectUri(),
                host,
                isLoopback(uri),
                pending.getExpiresAt());
    }

    public record Decision(String redirectUrl) {}

    /** The signed-in person agrees. The code is handed to the client through the browser, once. */
    @Transactional
    public Decision approve(String requestId) {
        var pending = pending(requestId);
        String code = CODE_PREFIX + random(32);
        pending.approve(scope.accountId(), digest(code));
        audit.recordAdmin(
                AuditAction.MCP_CLIENT_AUTHORIZED, null, pending.getClient().getClientName());
        var url = UriComponentsBuilder.fromUriString(pending.getRedirectUri())
                .queryParam("code", code)
                .queryParam("iss", issuer);
        if (pending.getState() != null) url.queryParam("state", pending.getState());
        return new Decision(url.encode().build().toUriString());
    }

    /** The signed-in person refuses. The client is told so in the words RFC 6749 gives it. */
    @Transactional
    public Decision deny(String requestId) {
        var pending = pending(requestId);
        authorizations.delete(pending);
        return new Decision(back(pending.getRedirectUri(), pending.getState(), "access_denied", null)
                .toString());
    }

    private McpAuthorization pending(String requestId) {
        return authorizations
                .findById(requestId == null ? "" : requestId)
                .filter(row -> !row.decided() && !row.expired())
                .orElseThrow(() -> new NotFoundException("This authorisation request has expired or was already used"));
    }

    /* ── Tokens ────────────────────────────────────────────────────────── */

    /**
     * The code comes back with the verifier that proves the same client asked for it.
     *
     * <p>A refusal commits: the code it consumed stays consumed. Rolling back would hand a second
     * attempt to whoever just failed the first.
     */
    @Transactional(noRollbackFor = OAuthException.class)
    public TokenResponse exchangeCode(
            String code, String redirectUri, String clientId, String codeVerifier, String resource) {
        if (code == null || codeVerifier == null || redirectUri == null)
            throw OAuthException.invalidRequest("code, code_verifier and redirect_uri are required");
        checkResource(resource);
        var pending = authorizations
                .findByCodeHash(digest(code))
                .orElseThrow(() -> OAuthException.invalidGrant("The code is unknown, expired or already used"));
        // Consumed before anything else is checked, and whatever the outcome: a code is one attempt.
        if (authorizations.consume(pending.getId()) != 1)
            throw OAuthException.invalidGrant("The code is unknown, expired or already used");
        if (pending.expired()) throw OAuthException.invalidGrant("The code is unknown, expired or already used");
        if (!pending.getClient().getId().toString().equals(clientId))
            throw OAuthException.invalidGrant("The code was issued to another client");
        if (!pending.getRedirectUri().equals(redirectUri))
            throw OAuthException.invalidGrant("redirect_uri differs from the one the code was issued for");
        if (!MessageDigest.isEqual(
                s256(codeVerifier).getBytes(StandardCharsets.US_ASCII),
                pending.getCodeChallenge().getBytes(StandardCharsets.US_ASCII)))
            throw OAuthException.invalidGrant("The code_verifier does not match the code_challenge");

        var account = accounts.findById(pending.getAccountId())
                .filter(a -> a.isEnabled())
                .orElseThrow(() -> OAuthException.invalidGrant("The account that agreed is no longer enabled"));
        var connection = new McpConnection(pending.getClient(), account.getId());
        var response = issue(connection);
        connections.save(connection);
        return response;
    }

    /** Rotates both tokens. A refresh token already rotated away ends its connection, and that commits. */
    @Transactional(noRollbackFor = OAuthException.class)
    public TokenResponse refresh(String refreshToken, String clientId, String resource) {
        if (refreshToken == null) throw OAuthException.invalidRequest("refresh_token is required");
        checkResource(resource);
        String hash = digest(refreshToken);
        var connection = connections.findByRefreshTokenHash(hash).orElse(null);
        if (connection == null) {
            connections.findByPreviousRefreshHash(hash).ifPresent(replayed -> {
                connections.delete(replayed);
                log.warn("An MCP refresh token was presented twice; connection {} was revoked", replayed.getId());
            });
            throw OAuthException.invalidGrant("The refresh token is unknown, expired or already used");
        }
        if (!connection.getClient().getId().toString().equals(clientId))
            throw OAuthException.invalidGrant("The refresh token was issued to another client");
        if (!Instant.now().isBefore(connection.getRefreshExpiresAt()))
            throw OAuthException.invalidGrant("The refresh token is unknown, expired or already used");
        if (resolve(connection).isEmpty()) {
            connections.delete(connection);
            throw OAuthException.invalidGrant("The account behind this connection can no longer authorise it");
        }
        return issue(connection);
    }

    /** RFC 7009: either token ends the whole connection, and the answer never says whether it existed. */
    @Transactional
    public void revoke(String token) {
        if (token == null || token.isBlank()) return;
        String hash = digest(token);
        connections
                .findByAccessTokenHash(hash)
                .or(() -> connections.findByRefreshTokenHash(hash))
                .ifPresent(connections::delete);
    }

    private TokenResponse issue(McpConnection connection) {
        String access = ACCESS_PREFIX + random(32);
        String refresh = REFRESH_PREFIX + random(32);
        var now = Instant.now();
        connection.issue(digest(access), now.plus(accessTtl), digest(refresh), now.plus(refreshTtl));
        return TokenResponse.bearer(access, accessTtl.toSeconds(), refresh);
    }

    /* ── Presenting a token at /mcp ────────────────────────────────────── */

    /** Who a request speaks for, and through which assistant. */
    public record Caller(ConsoleUser user, UUID connectionId, String clientName) {}

    /**
     * The person an access token acts for, read fresh on every call: a role changed, an account
     * disabled or a password reset takes effect on the assistant's next request, not when its token
     * runs out.
     */
    @Transactional
    public Optional<Caller> authenticate(String accessToken) {
        if (accessToken == null || !accessToken.startsWith(ACCESS_PREFIX)) return Optional.empty();
        var connection = connections.findByAccessTokenHash(digest(accessToken)).orElse(null);
        if (connection == null || !Instant.now().isBefore(connection.getAccessExpiresAt())) return Optional.empty();
        var user = resolve(connection);
        user.ifPresent(u -> connection.touch(Instant.now()));
        return user.map(
                u -> new Caller(u, connection.getId(), connection.getClient().getClientName()));
    }

    /** The account, provided it may still vouch for a consent it gave. */
    private Optional<ConsoleUser> resolve(McpConnection connection) {
        return accounts.findById(connection.getAccountId())
                .filter(account -> account.isEnabled())
                // A consent given before the password changed was given by somebody who may no longer
                // be the only one who knew it. Changing a password ends every session; this is one.
                .filter(account -> account.getPasswordChangedAt() == null
                        || !connection.getAuthorizedAt().isBefore(account.getPasswordChangedAt()))
                .map(ConsoleUser::new);
    }

    /* ── The console's view ────────────────────────────────────────────── */

    public record ConnectionView(
            UUID id, String clientName, Instant createdAt, Instant lastUsedAt, Instant expiresAt) {}

    @Transactional(readOnly = true)
    public List<ConnectionView> list() {
        return connections.findAllOwnedBy(scope.ownerFilter()).stream()
                .map(c -> new ConnectionView(
                        c.getId(),
                        c.getClient().getClientName(),
                        c.getAuthorizedAt(),
                        c.getLastUsedAt(),
                        c.getRefreshExpiresAt()))
                .toList();
    }

    @Transactional
    public void disconnect(UUID id) {
        var connection = connections
                .findOwnedBy(id, scope.ownerFilter())
                .orElseThrow(() -> new NotFoundException("Connection not found"));
        connections.delete(connection);
        audit.recordAdmin(
                AuditAction.MCP_CLIENT_REVOKED, null, connection.getClient().getClientName());
    }

    /**
     * Housekeeping. Requests nobody decided on, codes nobody redeemed, connections whose refresh token
     * ran out, and clients that registered a day ago and were never let in.
     */
    @Scheduled(fixedDelayString = "${janus.mcp.sweep-millis:900000}")
    @Transactional
    public void sweep() {
        var now = Instant.now();
        authorizations.deleteExpired(now);
        connections.deleteExpired(now);
        clients.deleteUnusedBefore(now.minus(Duration.ofDays(1)));
    }

    /* ── Helpers ───────────────────────────────────────────────────────── */

    private Optional<McpClient> findClient(String clientId) {
        if (clientId == null) return Optional.empty();
        try {
            return clients.findById(UUID.fromString(clientId.trim()));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private void checkResource(String resource) {
        if (resource != null && !sameResource(resource))
            throw new OAuthException(
                    "invalid_target", HttpStatus.BAD_REQUEST, "This server issues tokens for " + resource() + " only");
    }

    /** A trailing slash names the same resource; clients disagree on whether to send one. */
    private boolean sameResource(String presented) {
        return trimSlash(presented).equals(resource()) || trimSlash(presented).equals(issuer);
    }

    private URI console(Map<String, String> query) {
        var url = UriComponentsBuilder.fromUriString(consoleUrl + "/mcp/authorize");
        query.forEach(url::queryParam);
        return url.encode().build().toUri();
    }

    private URI back(String redirect, String state, String error, String description) {
        var url = UriComponentsBuilder.fromUriString(redirect).queryParam("error", error);
        if (description != null) url.queryParam("error_description", description);
        if (state != null) url.queryParam("state", state);
        url.queryParam("iss", issuer);
        return url.encode().build().toUri();
    }

    static String s256(String verifier) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the platform", ex);
        }
    }

    static String digest(String token) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the platform", ex);
        }
    }

    private static String random(int bytes) {
        byte[] material = new byte[bytes];
        RANDOM.nextBytes(material);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(material);
    }

    private static String trimSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
