package io.janus.shared;

import java.util.Locale;

/**
 * The stable name of a refusal, carried as {@code code} in every problem document and repeated in the
 * {@code X-Janus-Error} response header.
 *
 * <p>{@code detail} is prose. It is written for whoever is reading, it is expected to be reworded,
 * and nothing should ever branch on it. This is the half that may be: two refusals a caller has to
 * handle differently never share a code, and a code never changes meaning once it has been released.
 *
 * <p>The header exists so the distinction survives without parsing a body — and, more importantly,
 * so a caller can tell a refusal Janus made from one the upstream API made. An upstream 403 is
 * relayed exactly as it arrived and carries no such header; a 403 from Janus always does.
 */
public enum ErrorCode {

    // Who the caller is.
    /** No usable application credential was presented to the gateway. */
    AUTHENTICATION_REQUIRED,
    /** Too many failed authentication attempts from this client; it is blocked for a while. */
    AUTHENTICATION_THROTTLED,
    /** The console session is absent or has expired. */
    NOT_SIGNED_IN,
    /** Authenticated, but not permitted to make this particular request. */
    FORBIDDEN,

    // The shape of the request.
    /** The request path is read differently by different layers and was refused before routing. */
    PATH_AMBIGUOUS,
    /** The portion of the path addressed to the upstream cannot be forwarded safely. */
    PATH_INVALID,
    /** The request body is larger than this deployment accepts. */
    PAYLOAD_TOO_LARGE,
    /** The HTTP method is not one this endpoint or the gateway handles. */
    METHOD_NOT_SUPPORTED,
    /** The request's content type is not one this endpoint reads. */
    UNSUPPORTED_MEDIA_TYPE,
    /** One or more fields were rejected; the {@code errors} member names them. */
    VALIDATION_FAILED,
    /** The body could not be parsed at all. */
    MALFORMED_BODY,
    /** A request the endpoint understood and still cannot act on. */
    BAD_REQUEST,
    /** No such record. */
    NOT_FOUND,
    /** The write conflicts with an existing record, or with one still referencing it. */
    CONFLICT,

    // Whether this caller may reach this destination.
    /** No enabled provider is registered under that slug. */
    PROVIDER_UNAVAILABLE,
    /** This application holds no active grant for that provider. */
    GRANT_MISSING,
    /** A grant exists, but the credential behind it has been disabled. */
    CREDENTIAL_DISABLED,
    /**
     * The grant admits only part of this destination, and the path asked for is outside it. Its own
     * code rather than the one above: the caller does hold a grant, and what has to change is which
     * paths it names, which is an administrative decision rather than a missing one.
     */
    PATH_NOT_GRANTED,
    /** The grant admits this path and not this method. Read-only access is the usual reason. */
    METHOD_NOT_GRANTED,
    /**
     * The call asked to speak for the connected account, and nobody has connected one. Its own code
     * rather than a refusal from upstream: this is repaired by one person agreeing once, in the
     * console, and a caller that reads codes can say so instead of reporting a failure.
     */
    CONNECTION_NOT_AUTHORISED,

    // Allowances.
    /** The per-address ceiling on calls to Janus itself. */
    RATE_LIMIT_CLIENT,
    /** This application's own allowance for this provider. */
    RATE_LIMIT_GRANT,
    /** The provider's allowance, shared by every caller of it. */
    RATE_LIMIT_PROVIDER,
    /** The provider asked for a pause and Janus is honouring it for everyone. */
    PROVIDER_COOLDOWN,

    // Reaching the upstream.
    /** The provider's registered address is not usable under this deployment's rules. */
    PROVIDER_MISCONFIGURED,
    /** The destination resolved to an address the gateway may not connect to. */
    DESTINATION_BLOCKED,
    /** Janus could not obtain the token this credential needs, so nothing was sent. */
    TOKEN_EXCHANGE_FAILED,
    /** The upstream could not be reached: connection refused, DNS, or TLS. */
    UPSTREAM_UNREACHABLE,
    /** The upstream accepted the connection and did not answer in time. */
    UPSTREAM_TIMEOUT,
    /** The upstream answered, but not in a way the exchange could complete on. */
    UPSTREAM_FAILED,

    // Janus itself.
    /** A failure inside Janus. Never the upstream's fault, and always logged with its stack. */
    INTERNAL_ERROR;

    private final String wire = name().toLowerCase(Locale.ROOT);

    /** The form that travels: lower snake case, and part of the API's contract. */
    public String wire() {
        return wire;
    }
}
