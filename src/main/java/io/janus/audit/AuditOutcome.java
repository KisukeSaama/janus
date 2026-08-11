package io.janus.audit;

/** How an attempt ended. */
public enum AuditOutcome {
    /** The operation was carried out. */
    SUCCESS,
    /** Janus refused it on purpose: no grant, no route, disabled record, bad credentials. */
    DENIED,
    /** Refused to protect an allowance rather than for lack of permission. */
    THROTTLED,
    /** The upstream, or Janus itself, failed while carrying it out. */
    ERROR
}
