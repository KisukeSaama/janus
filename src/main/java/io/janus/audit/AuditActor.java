package io.janus.audit;

/** Who caused an event. */
public enum AuditActor {
    /** An authenticated console administrator. */
    ADMIN,
    /** A client application calling the gateway. */
    APPLICATION,
    /** Janus acting on its own schedule, such as the daily expiry sweep. */
    SYSTEM
}
