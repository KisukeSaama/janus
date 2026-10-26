package io.janus.openbao;

/**
 * The vault could not be used: unreachable, unconfigured, or refusing the token it was handed.
 *
 * <p>Its own type so it can be answered as what it is, an outage of a dependency, rather than as a
 * defect in Janus. Still an {@link IllegalStateException}, so code that already catches those keeps
 * working. The message is for the log only: it is never quoted back to a caller.
 */
public class SecretStoreUnavailableException extends IllegalStateException {

    public SecretStoreUnavailableException(String message) {
        super(message);
    }
}
