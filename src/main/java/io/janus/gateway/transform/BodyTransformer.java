package io.janus.gateway.transform;

import org.springframework.http.MediaType;

/**
 * One format Janus can restate as JSON.
 *
 * <p>A transformer produces a model — nested {@link java.util.Map}s, {@link java.util.List}s and
 * strings — rather than bytes. Serialising is done once, in {@link JsonNormalizer}, so no
 * implementation has to think about escaping and every format comes out written the same way.
 *
 * <p>Implementations are stateless and shared across requests.
 */
interface BodyTransformer {

    /** What this conversion is called in {@code X-Janus-Transform}; lowercase, ASCII, no spaces. */
    String name();

    /** Whether this transformer claims the response, decided on its {@code Content-Type} alone. */
    boolean handles(MediaType contentType);

    /**
     * @param arrays which names must come out as arrays even when seen once; see {@link ArrayPaths}
     * @throws BodyTransformException when the body is not what its {@code Content-Type} said it was.
     *     The caller then receives the original bytes: a conversion never turns a good response into
     *     an error.
     */
    Object read(byte[] body, MediaType contentType, ArrayPaths arrays) throws BodyTransformException;
}
