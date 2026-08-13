package io.janus.gateway.transform;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import io.janus.gateway.GatewayTrafficProperties;

/**
 * Restates a response as JSON, so a client service can talk to every destination the same way.
 *
 * <p>This is the last of the things Janus does on its callers' behalf, and it is the same argument
 * as the others: a self-hosted deployment's most useful APIs answer in whatever format they were
 * written around, and without this each client carries a parser for a format it never chose. Two
 * clients of the same API carry two, and they disagree.
 *
 * <p><strong>Which converter runs is decided by the response, not by configuration.</strong> A
 * destination is marked as normalising and nothing more; the {@code Content-Type} that comes back
 * picks the transformer. An API answering XML on one route and JSON on another therefore needs no
 * special handling, which matters because that is the common case rather than the exception — Plex
 * itself does it.
 *
 * <p><strong>A conversion is never a way for a request to fail.</strong> Every refusal below ends
 * with the upstream's own bytes being returned unchanged, and the reason is stated in
 * {@code X-Janus-Transform} instead. A caller that receives XML where it expected JSON has a header
 * saying why; a caller that receives a 502 has nothing, and Janus would have invented a failure the
 * upstream never had.
 */
@Component
public class JsonNormalizer {
    private static final Logger log = LoggerFactory.getLogger(JsonNormalizer.class);

    /** What was done, or why nothing was. Never carries response content. */
    public static final String TRANSFORM_HEADER = "X-Janus-Transform";

    private final List<BodyTransformer> transformers;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final int maxBytes;

    public JsonNormalizer(ObjectMapper mapper, GatewayTrafficProperties properties) {
        this.mapper = mapper;
        this.transformers = List.of(new XmlToJson(), new FormToJson(), new NdjsonToJson(mapper));
        this.enabled = properties.transform().enabled();
        this.maxBytes = properties.transform().maxBytes();
    }

    /**
     * @param body      what the caller would otherwise receive
     * @param converted whether {@code body} is the JSON restatement rather than the original bytes.
     *                  The caller uses this to drop the upstream's validators: an {@code ETag}
     *                  describes the representation it was issued for, and a client holding one for a
     *                  document it never received would revalidate against the wrong thing.
     * @param note      for {@code X-Janus-Transform}, or null when there was nothing to say
     */
    public record Outcome(byte[] body, boolean converted, String note) {

        static Outcome unchanged(byte[] body) {
            return new Outcome(body, false, null);
        }

        static Outcome declined(byte[] body, String reason) {
            return new Outcome(body, false, "none (" + reason + ")");
        }
    }

    /** Whether this deployment offers normalisation at all. */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * @param requestHeaders the caller's own, for the {@code Accept} that lets it ask for the
     *                       original instead
     */
    public Outcome normalize(byte[] body, HttpHeaders responseHeaders, HttpHeaders requestHeaders, ArrayPaths arrays) {
        if (!enabled || body == null || body.length == 0) return Outcome.unchanged(body);

        // Substituting inside a compressed body would produce neither valid JSON nor a valid
        // encoding. The same reasoning keeps SecretRedactor away from one.
        String encoding = responseHeaders.getFirst(HttpHeaders.CONTENT_ENCODING);
        if (encoding != null && !"identity".equalsIgnoreCase(encoding.trim()))
            return Outcome.declined(body, "body is " + encoding.trim());

        MediaType contentType = contentType(responseHeaders);
        if (contentType == null) return Outcome.declined(body, "no content type");
        // Already what the caller wanted. Reading and rewriting it would spend time to produce the
        // same document with different whitespace.
        if (isJson(contentType)) return Outcome.unchanged(body);
        if (clientPrefersOriginal(requestHeaders, contentType)) return Outcome.unchanged(body);

        var transformer = transformers.stream()
                .filter(candidate -> candidate.handles(contentType))
                .findFirst()
                .orElse(null);
        if (transformer == null) return Outcome.declined(body, "no converter for " + contentType.toString());

        // Conversion expands: an object per row, a key repeated per record, quotes around values XML
        // left bare. A body inside JANUS_MAX_RESPONSE_BYTES can leave several times larger, and that
        // multiplied by the calls in flight is the number that reaches the heap.
        if (body.length > maxBytes) return Outcome.declined(body, "body over " + maxBytes + " bytes");

        try {
            byte[] json = mapper.writeValueAsBytes(transformer.read(body, contentType, arrays));
            return new Outcome(json, true, transformer.name() + "->json");
        } catch (BodyTransformException ex) {
            // Expected: the upstream sent something other than what it announced. The caller gets
            // what was actually sent, with the reason attached.
            return Outcome.declined(body, ex.getMessage());
        } catch (JacksonException ex) {
            return Outcome.declined(body, "converted document could not be written as JSON");
        } catch (RuntimeException ex) {
            // Not expected. Logged with its stack trace because it is a defect here rather than a
            // malformed response, and still never allowed to cost the caller its answer.
            log.warn("Normalising a {} response failed unexpectedly", contentType, ex);
            return Outcome.declined(body, "converter failed");
        }
    }

    /** Null rather than throwing: an upstream is free to send a {@code Content-Type} nothing parses. */
    private static MediaType contentType(HttpHeaders headers) {
        try {
            return headers.getContentType();
        } catch (InvalidMediaTypeException ex) {
            return null;
        }
    }

    private static boolean isJson(MediaType contentType) {
        return contentType.isCompatibleWith(MediaType.APPLICATION_JSON)
                || contentType.getSubtype().endsWith("+json");
    }

    /**
     * Whether the caller asked for the format the upstream speaks.
     *
     * <p>A destination is marked as normalising because that is what its callers want by default, so
     * the question here is only whether this one caller opted out — which it does by naming the
     * original type and not naming JSON. {@code Accept: application/xml} gets XML;
     * {@code Accept: application/xml, application/json} gets JSON, because it said both were welcome
     * and the destination's own setting decides between them. A wildcard names nothing and therefore
     * opts out of nothing.
     */
    private static boolean clientPrefersOriginal(HttpHeaders requestHeaders, MediaType contentType) {
        List<MediaType> accepted;
        try {
            accepted = requestHeaders.getAccept();
        } catch (InvalidMediaTypeException ex) {
            return false;
        }
        boolean namesOriginal = false;
        boolean namesJson = false;
        for (MediaType type : accepted) {
            if (type.isWildcardType() || type.isWildcardSubtype()) continue;
            if (type.isCompatibleWith(contentType)) namesOriginal = true;
            if (isJson(type)) namesJson = true;
        }
        return namesOriginal && !namesJson;
    }
}
