package io.janus.gateway.transform;

/**
 * A body that could not be read as the format its {@code Content-Type} announced.
 *
 * <p>Checked, deliberately: every conversion has to state what it does when the bytes disagree with
 * the header, and the answer is always the same — hand back what the upstream sent. The message is
 * about the shape of the document and never quotes its content, because it is written into the
 * response headers and the audit trail.
 */
public class BodyTransformException extends Exception {

    public BodyTransformException(String message) {
        super(message);
    }

    public BodyTransformException(String message, Throwable cause) {
        super(message, cause);
    }
}
