package io.janus.gateway.transform;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import org.springframework.http.MediaType;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Newline-delimited JSON gathered into a single array — the shape exports and log endpoints answer
 * in, so a caller can parse the response with the JSON reader it already has.
 *
 * <p>This one genuinely takes something away. NDJSON exists so a consumer can act on the first
 * record before the last one is written, and an array cannot be read until it is complete. It costs
 * nothing here only because Janus already buffers every response whole — for the size limit, the
 * response store, and the credential scrub — so the streaming was gone before this class saw it.
 *
 * <p>A line that is not valid JSON abandons the conversion rather than being skipped. Dropping it
 * would hand the caller a shorter array with no indication that anything was missing, and a silently
 * incomplete answer is worse than the original bytes.
 */
final class NdjsonToJson implements BodyTransformer {

    private final ObjectMapper mapper;

    NdjsonToJson(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "ndjson";
    }

    @Override
    public boolean handles(MediaType contentType) {
        if (contentType == null) return false;
        String subtype = contentType.getSubtype();
        return "application".equals(contentType.getType())
                && ("x-ndjson".equals(subtype)
                        || "ndjson".equals(subtype)
                        || "jsonl".equals(subtype)
                        || "x-jsonlines".equals(subtype)
                        || "x-ldjson".equals(subtype));
    }

    @Override
    public Object read(byte[] body, MediaType contentType, ArrayPaths arrays) throws BodyTransformException {
        Charset charset = contentType == null || contentType.getCharset() == null
                ? StandardCharsets.UTF_8
                : contentType.getCharset();

        var records = new ArrayList<Object>();
        int number = 0;
        for (String line : new String(body, charset).split("\\R")) {
            number++;
            if (line.isBlank()) continue;
            try {
                records.add(mapper.readTree(line));
            } catch (JacksonException ex) {
                // The line number locates the problem for whoever has to look at the upstream; the
                // line itself is response content and stays out of the header this message reaches.
                throw new BodyTransformException("Line " + number + " of the NDJSON body is not valid JSON", ex);
            }
        }
        return records;
    }
}
