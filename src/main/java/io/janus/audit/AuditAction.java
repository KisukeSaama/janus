package io.janus.audit;

/**
 * What was attempted. The stream itself keeps actions as text, because a journal must be able to
 * read back an entry written by a version of Janus that knew an action this one has since dropped;
 * this enum is what the rest of the code is allowed to write.
 */
public enum AuditAction {
    APPLICATION_CREATED,
    APPLICATION_UPDATED,
    APPLICATION_DELETED,
    APPLICATION_KEY_ROTATED,

    PROVIDER_CREATED,
    PROVIDER_UPDATED,
    PROVIDER_DELETED,
    PROVIDER_CACHE_PURGED,

    CREDENTIAL_CREATED,
    CREDENTIAL_UPDATED,
    CREDENTIAL_DELETED,
    CREDENTIAL_EXPIRY_NOTICE,
    CREDENTIAL_EXPIRY_WARNING,
    CREDENTIAL_EXPIRED,
    // A person was sent to a provider to agree, came back having agreed, or withdrew it afterwards.
    // Recorded separately from an ordinary edit because the subject of the record is somebody's
    // consent rather than an administrator's configuration.
    CREDENTIAL_AUTHORIZATION_STARTED,
    CREDENTIAL_AUTHORIZED,
    CREDENTIAL_AUTHORIZATION_REVOKED,

    GRANT_CREATED,
    GRANT_UPDATED,
    GRANT_DELETED,

    GATEWAY_REQUEST,
    GATEWAY_AUTHENTICATION,
    GATEWAY_CACHE_PURGED,

    OAUTH_TOKEN_ISSUED,
    OAUTH_TOKEN_REPLAYED,

    ACCOUNT_CREATED,
    ACCOUNT_UPDATED,
    ACCOUNT_DELETED,
    ACCOUNT_PASSWORD_CHANGED,
    ACCOUNT_RECORDS_TRANSFERRED,
    ACCOUNT_SIGNED_IN,
    ACCOUNT_SIGNED_OUT,

    ADMIN_AUTHENTICATION
}
