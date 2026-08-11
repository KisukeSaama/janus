package io.janus.providers;

/**
 * Whether a registered destination answered, asked just now.
 *
 * <p>The question is smaller than "does a call work": nothing is presented and nothing is read back,
 * so a 401 or a 404 still counts as reached. What this separates is a destination that cannot be
 * talked to at all — a name that no longer resolves, a certificate that expired, a host that stopped
 * listening — from one that is answering and refusing, which is a different fault with a different
 * fix and is diagnosed on the credential rather than on the address.
 *
 * @param reachable whether an HTTP answer came back, whatever it said
 * @param status what it answered, or zero when nothing did
 * @param millis how long the probe took, answered or not
 * @param reason why it ended that way, as a value the console can name
 */
public record ProviderPing(boolean reachable, int status, long millis, Reason reason) {

    /** Named causes rather than a message: a probe's failure is shown to a reader, not logged at one. */
    public enum Reason {
        /** Something is listening and it replied. */
        ANSWERED,
        /** Nothing came back before the probe's own deadline. */
        TIMED_OUT,
        /** The host name does not resolve. */
        UNRESOLVED,
        /** The TLS handshake could not be completed — commonly an expired or mismatched certificate. */
        TLS_FAILED,
        /** The name resolves to an address the gateway is not allowed to reach. */
        BLOCKED,
        /** Refused, reset, or no route: reached the network and got nowhere. */
        UNREACHABLE
    }

    static ProviderPing answered(int status, long millis) {
        return new ProviderPing(true, status, millis, Reason.ANSWERED);
    }

    static ProviderPing failed(Reason reason, long millis) {
        return new ProviderPing(false, 0, millis, reason);
    }
}
