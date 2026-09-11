package io.janus.gateway.graphql;

import java.util.Locale;
import java.util.Set;

import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What a GraphQL answer says about itself, for the decisions HTTP's status line cannot inform.
 *
 * <p>A GraphQL server answers {@code 200} to a query it could not run, and puts the failure in an
 * {@code errors} array. Everything Janus decides from the status therefore reads a refusal as a
 * success: it would store it, share it with every caller of the credential, and journal it as a call
 * that worked. This is the second reading, done only for GraphQL operations and only on JSON.
 */
public final class GraphQlResponses {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /**
     * The codes the common servers write into {@code extensions.code} when the caller, rather than
     * the operation, is the problem. Apollo and most of its descendants use the first two; Hasura and
     * a few others spell it differently.
     */
    private static final Set<String> REFUSALS =
            Set.of("UNAUTHENTICATED", "FORBIDDEN", "UNAUTHORIZED", "ACCESS_DENIED", "INVALID-JWT", "ACCESS-DENIED");

    /** Bodies larger than this are not read for a summary; the answer is relayed exactly as it came. */
    private static final int MAX_READ_BYTES = 2 * 1024 * 1024;

    private GraphQlResponses() {}

    /**
     * @param errors  how many errors the answer carries; zero for a clean one and for anything unread
     * @param refused whether it carries no data and every error says the caller may not do this, which
     *                is a GraphQL server's way of answering 401 or 403
     */
    public record Summary(int errors, boolean refused) {
        public static final Summary CLEAN = new Summary(0, false);
    }

    public static Summary read(HttpHeaders headers, byte[] body) {
        if (body == null || body.length == 0 || body.length > MAX_READ_BYTES || !json(headers)) return Summary.CLEAN;
        JsonNode document;
        try {
            document = MAPPER.readTree(body);
        } catch (JacksonException ex) {
            return Summary.CLEAN;
        }
        // A batch answers with an array; its errors are the sum of its members'.
        int errors = 0;
        boolean everyRefused = true;
        boolean any = false;
        for (JsonNode answer : document.isArray() ? document : java.util.List.of(document)) {
            if (!answer.isObject()) continue;
            any = true;
            var list = answer.get("errors");
            int here = list != null && list.isArray() ? list.size() : 0;
            errors += here;
            everyRefused &= here > 0 && noData(answer) && allRefusals(list);
        }
        return new Summary(errors, any && everyRefused);
    }

    private static boolean noData(JsonNode answer) {
        var data = answer.get("data");
        return data == null || data.isNull();
    }

    private static boolean allRefusals(JsonNode errors) {
        for (JsonNode error : errors) {
            var code = error.path("extensions").path("code");
            if (!code.isString() || !REFUSALS.contains(code.stringValue().toUpperCase(Locale.ROOT))) return false;
        }
        return true;
    }

    private static boolean json(HttpHeaders headers) {
        try {
            var type = headers.getContentType();
            return type != null
                    && (type.isCompatibleWith(MediaType.APPLICATION_JSON)
                            || type.getSubtype().endsWith("+json"));
        } catch (InvalidMediaTypeException ex) {
            return false;
        }
    }
}
