package io.janus.accounts;

/**
 * What a person may do in the console.
 *
 * <p>Every role owns its applications, credentials and grants. Administrators additionally manage
 * accounts and the shared API catalogue; they still cannot read another account's secret material.
 */
public enum AccountRole {
    /** Manages every account, administrators included, and is the only role that can. */
    SUPER_ADMIN,
    /** Creates accounts and appoints administrators, but never edits another administrator's. */
    ADMIN,
    /** Holds their own registry. The ordinary account, and what most people are. */
    USER;

    /** Whether this role manages accounts and the shared API catalogue. */
    public boolean administers() {
        return this == SUPER_ADMIN || this == ADMIN;
    }
}
