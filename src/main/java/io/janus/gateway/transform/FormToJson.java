package io.janus.gateway.transform;

import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;

import org.springframework.http.MediaType;

/**
 * A form-encoded body restated as JSON — {@code oauth_token=x&expires=3600} becoming an object.
 *
 * <p>Rarer than XML in a response, and worth having anyway: OAuth 1.0a token endpoints answer this
 * way, as do a handful of payment APIs old enough to predate JSON. It costs a screen of code because
 * the format is a list of pairs and nothing else.
 *
 * <p>A name appearing twice becomes an array, on the same reasoning and with the same weakness as in
 * {@link XmlToJson} — so {@link ArrayPaths} applies here too, by bare name, since a form body has no
 * nesting for a dotted path to describe. Values stay strings for the same reason as well.
 *
 * <p>A pair with no {@code =} is a name whose value is empty, which is what a browser sends for an
 * empty field and what every server-side parser reads it as.
 */
final class FormToJson implements BodyTransformer {

    @Override
    public String name() {
        return "form";
    }

    @Override
    public boolean handles(MediaType contentType) {
        return contentType != null && contentType.isCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED);
    }

    @Override
    public Object read(byte[] body, MediaType contentType, ArrayPaths arrays) throws BodyTransformException {
        Charset charset = contentType == null || contentType.getCharset() == null
                ? StandardCharsets.UTF_8
                : contentType.getCharset();

        var content = new LinkedHashMap<String, Object>();
        for (String pair : new String(body, charset).split("&")) {
            if (pair.isEmpty()) continue;
            int equals = pair.indexOf('=');
            String name = decode(equals < 0 ? pair : pair.substring(0, equals), charset);
            String value = equals < 0 ? "" : decode(pair.substring(equals + 1), charset);
            add(content, name, value, arrays.forcesArray(name));
        }
        return content;
    }

    private static String decode(String encoded, Charset charset) throws BodyTransformException {
        try {
            return URLDecoder.decode(encoded, charset);
        } catch (IllegalArgumentException ex) {
            // A stray % or a truncated escape. Naming the offending text would put response content
            // into a header, so only the shape of the failure is reported.
            throw new BodyTransformException("Form body holds an invalid percent-encoded sequence", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static void add(Map<String, Object> content, String name, String value, boolean forced) {
        Object existing = content.get(name);
        if (existing == null) {
            content.put(name, forced ? new ArrayList<>(List.of(value)) : value);
        } else if (existing instanceof List<?> list) {
            ((List<Object>) list).add(value);
        } else {
            var list = new ArrayList<>();
            list.add(existing);
            list.add(value);
            content.put(name, list);
        }
    }
}
