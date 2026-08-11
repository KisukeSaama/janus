package io.janus.oauth;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

import org.slf4j.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.janus.applications.ApplicationRepository;
import io.janus.audit.*;
import io.janus.security.ApplicationAuthenticator;
import io.janus.security.GatewayPrincipal;
import io.janus.shared.CorrelationIdFilter;

/**
 * The exchange: an application's own credentials in, a short-lived bearer token out.
 *
 * <p>The client id is the application's identifier and the client secret is its API key — the same
 * pair the console issues, and the same one the static headers carry. Nothing new to create, nothing
 * new to store, and a caller that already works keeps working: this endpoint is another way to
 * present the same secret, not a second secret.
 *
 * <p>Refresh tokens rotate. Using one retires it and issues a successor in the same family; a value
 * presented twice is evidence that it leaked, so the second attempt is refused <em>and</em> the whole
 * family is dropped. A client that legitimately loses a race is asked to authenticate again, which is
 * the right trade against honouring a stolen token.
 */
@Service
public class OAuthTokenService {
    private static final Logger log = LoggerFactory.getLogger(OAuthTokenService.class);

    private static final String PREFIX = "jnr_";
    private static final int ENTROPY_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApplicationAuthenticator authenticator;
    private final ApplicationRepository applications;
    private final RefreshTokenRepository refreshTokens;
    private final AccessTokenStore accessTokens;
    private final OAuthProperties properties;
    private final AuditService audit;

    public OAuthTokenService(
            ApplicationAuthenticator authenticator,
            ApplicationRepository applications,
            RefreshTokenRepository refreshTokens,
            AccessTokenStore accessTokens,
            OAuthProperties properties,
            AuditService audit) {
        this.authenticator = authenticator;
        this.applications = applications;
        this.refreshTokens = refreshTokens;
        this.accessTokens = accessTokens;
        this.properties = properties;
        this.audit = audit;
    }

    /** {@code grant_type=client_credentials}: the ordinary way in. */
    @Transactional
    public TokenResponse clientCredentials(String clientId, String clientSecret) {
        UUID applicationId = parse(clientId);
        var principal = authenticator.authenticate(applicationId, clientSecret).orElseThrow(() -> {
            // Never says which of the two was wrong, and never names the application.
            audit.recordAuthenticationDenied(
                    AuditActor.APPLICATION,
                    AuditAction.OAUTH_TOKEN_ISSUED,
                    "POST",
                    "/oauth/token",
                    401,
                    "Client authentication failed");
            return OAuthException.invalidClient();
        });
        return grant(principal, UUID.randomUUID());
    }

    /**
     * {@code grant_type=refresh_token}: coming back without the secret.
     *
     * <p>Everything the token stood for is checked again rather than trusted: the application may
     * have been disabled or deleted, and its key may have been rotated, since the token was issued.
     */
    @Transactional
    public TokenResponse refresh(String presented) {
        if (presented == null || presented.isBlank())
            throw OAuthException.invalidRequest("A refresh_token is required for this grant type");

        var stored = refreshTokens
                .findByTokenHash(AccessTokenStore.digest(presented))
                .orElseThrow(() -> OAuthException.invalidGrant("The refresh token is not valid"));

        if (stored.spent()) {
            // Presented twice. Whichever of the two holders is the thief, neither keeps the chain.
            refreshTokens.deleteByFamilyId(stored.getFamilyId());
            accessTokens.revokeApplication(stored.getApplicationId());
            audit.recordAuthenticationDenied(
                    AuditActor.APPLICATION,
                    AuditAction.OAUTH_TOKEN_REPLAYED,
                    "POST",
                    "/oauth/token",
                    400,
                    "A refresh token was presented twice; its family was revoked");
            throw OAuthException.invalidGrant("The refresh token has already been used");
        }
        if (stored.expired(Instant.now())) {
            refreshTokens.delete(stored);
            throw OAuthException.invalidGrant("The refresh token has expired");
        }

        var application = applications
                .findByIdWithOwner(stored.getApplicationId())
                .filter(io.janus.applications.Application::isEnabled)
                .orElseThrow(() -> {
                    refreshTokens.deleteByFamilyId(stored.getFamilyId());
                    return OAuthException.invalidGrant("The service this token was issued to is no longer available");
                });

        stored.use();
        var principal = new GatewayPrincipal(
                application.getId(),
                application.getName(),
                application.getOwner().getId(),
                application.getOwner().getUsername(),
                application.getAllowedOrigins());
        return grant(principal, stored.getFamilyId());
    }

    /** Drops a token, whichever of the two kinds it is. Answers 200 either way, as RFC 7009 requires. */
    @Transactional
    public void revoke(String token) {
        if (token == null || token.isBlank()) return;
        if (accessTokens.revoke(token)) return;
        refreshTokens.findByTokenHash(AccessTokenStore.digest(token)).ifPresent(stored -> {
            // Revoking one link revokes the chain: the client is asking to be forgotten, not to be
            // handed the next token in the sequence.
            refreshTokens.deleteByFamilyId(stored.getFamilyId());
            accessTokens.revokeApplication(stored.getApplicationId());
        });
    }

    private TokenResponse grant(GatewayPrincipal principal, UUID familyId) {
        long ttl = properties.accessTokenTtl().toSeconds();
        String accessToken = accessTokens.issue(principal, ttl);

        String refreshToken = null;
        if (properties.refreshEnabled()) {
            refreshToken = PREFIX + randomValue();
            refreshTokens.save(new RefreshToken(
                    principal.applicationId(),
                    AccessTokenStore.digest(refreshToken),
                    familyId,
                    Instant.now().plus(properties.refreshTokenTtl())));
        }

        audit.recordGateway(new AuditService.GatewayEvent(
                principal.applicationId(),
                principal.ownerId(),
                AuditOutcome.SUCCESS,
                null,
                "POST",
                "/oauth/token",
                200,
                "Issued an access token valid for " + ttl + "s",
                CorrelationIdFilter.current()));
        return TokenResponse.bearer(accessToken, ttl, refreshToken);
    }

    private static String randomValue() {
        byte[] material = new byte[ENTROPY_BYTES];
        RANDOM.nextBytes(material);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(material);
    }

    /**
     * Drops the refresh tokens that have run out.
     *
     * <p>Rotation retires a row rather than removing it, because a value presented after it was
     * retired is what proves a leak — but only until it expires, after which the row is refused on
     * its date alone and proves nothing anybody can act on. Without this they accumulate one per
     * exchange, for the lifetime of the deployment, and every one of them is dead weight.
     *
     * <p>Hourly rather than on a time of day: nothing about an expiry is tied to the morning, and the
     * work is a single indexed delete.
     */
    @Scheduled(fixedDelayString = "${janus.oauth.refresh-sweep-millis:3600000}")
    @Transactional
    public void sweepExpired() {
        int dropped = refreshTokens.deleteExpiredBefore(Instant.now());
        if (dropped > 0) log.debug("Swept {} refresh token(s) that had expired", dropped);
    }

    /** A client id that is not an identifier fails as a credential, not as a malformed request. */
    private static UUID parse(String clientId) {
        if (clientId == null || clientId.isBlank())
            throw OAuthException.invalidRequest("A client_id is required, in the body or via Basic authentication");
        try {
            return UUID.fromString(clientId.trim());
        } catch (IllegalArgumentException ex) {
            throw OAuthException.invalidClient();
        }
    }

    /** Decodes the {@code Basic} form of client authentication described by RFC 6749 §2.3.1. */
    public static Optional<String[]> basicCredentials(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Basic ", 0, 6)) return Optional.empty();
        try {
            String decoded = new String(
                    Base64.getDecoder().decode(authorization.substring(6).trim()), StandardCharsets.UTF_8);
            int separator = decoded.indexOf(':');
            if (separator < 0) return Optional.empty();
            return Optional.of(new String[] {decoded.substring(0, separator), decoded.substring(separator + 1)});
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
