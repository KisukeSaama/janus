package io.janus.accounts;

/**
 * What a person may do in the console.
 *
 * <p>The ladder governs accounts, and only accounts. Owning an API is not something a role
 * overrides: every role sees its own services, APIs, secrets and access rules and nobody else's, an
 * administrator included, and there is no supervising view of somebody else's registry, journal or
 * traffic. What administration buys is the right to say who may sign in. Nothing else.
 */
public enum AccountRole {
    /** Manages every account, administrators included, and is the only role that can. */
    SUPER_ADMIN,
    /** Creates accounts and appoints administrators, but never edits another administrator's. */
    ADMIN,
    /** Holds their own registry. The ordinary account, and what most people are. */
    USER;

    /** Whether this role manages accounts at all. It says nothing about reading anybody's records. */
    public boolean administers() {
        return this == SUPER_ADMIN || this == ADMIN;
    }
}
