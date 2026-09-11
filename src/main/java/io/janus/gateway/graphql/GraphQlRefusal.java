package io.janus.gateway.graphql;

import org.springframework.http.HttpStatus;

import io.janus.shared.ErrorCode;

/**
 * A GraphQL request Janus will not forward, with the status and code it is refused under. The message
 * is written for the caller and names the part of their own document at fault, never anything of the
 * upstream's.
 */
public class GraphQlRefusal extends RuntimeException {
    private final HttpStatus status;
    private final ErrorCode code;

    public GraphQlRefusal(HttpStatus status, ErrorCode code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    static GraphQlRefusal invalid(String message) {
        return new GraphQlRefusal(HttpStatus.BAD_REQUEST, ErrorCode.GRAPHQL_INVALID, message);
    }

    static GraphQlRefusal tooComplex(String message) {
        return new GraphQlRefusal(HttpStatus.BAD_REQUEST, ErrorCode.GRAPHQL_TOO_COMPLEX, message);
    }

    public HttpStatus status() {
        return status;
    }

    public ErrorCode code() {
        return code;
    }
}
