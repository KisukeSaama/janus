package io.janus.gateway;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.*;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import reactor.core.publisher.Flux;

/**
 * Relays a streamed answer (Server-Sent Events, or a multipart response) to the caller as it
 * arrives, rather than after it has finished, which for a subscription is never.
 *
 * <p>Relayed a line at a time, and that is the whole reason this is more than a pipe. Every other
 * answer is scrubbed of the credential before it is returned; a stream cannot be, as a whole, because
 * it has no end to wait for. Both formats are made of lines, and a secret an upstream echoes sits
 * inside one, so each line is scrubbed as it completes. Scrubbing arbitrary chunks instead would let
 * a value split across two network reads through in halves.
 *
 * <p>Pumped on a virtual thread of its own. Reading the upstream blocks, writing to the caller blocks,
 * and a virtual thread is what makes holding a few hundred of those open cost stacks rather than
 * threads.
 */
final class StreamRelay {
    private static final Logger log = LoggerFactory.getLogger(StreamRelay.class);

    /** A line longer than this is relayed in pieces; nothing legitimate in an event is this long. */
    static final int MAX_LINE_BYTES = 1024 * 1024;

    private final ResponseBodyEmitter emitter;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile java.util.stream.Stream<DataBuffer> upstream;
    private volatile Runnable onEnd = () -> {};

    /** @param timeoutMillis how long the stream may stay open at all; zero or less is no limit */
    StreamRelay(long timeoutMillis) {
        this.emitter = new ResponseBodyEmitter(timeoutMillis > 0 ? timeoutMillis : -1L);
        emitter.onCompletion(this::close);
        emitter.onTimeout(this::close);
        emitter.onError(failure -> close());
    }

    ResponseBodyEmitter emitter() {
        return emitter;
    }

    /** Starts relaying. {@code onEnd} runs once, however the stream ends. */
    void start(Flux<DataBuffer> body, String[] secrets, Runnable onEnd) {
        this.onEnd = onEnd;
        if (closed.get()) {
            onEnd.run();
            return;
        }
        Thread.ofVirtual().name("janus-stream").start(() -> pump(body, secrets));
    }

    private void pump(Flux<DataBuffer> body, String[] secrets) {
        var lines = new Lines(secrets);
        try (var chunks = body.toStream(1)) {
            upstream = chunks;
            if (closed.get()) return;
            var iterator = chunks.iterator();
            while (iterator.hasNext()) {
                var buffer = iterator.next();
                byte[] bytes = new byte[buffer.readableByteCount()];
                buffer.read(bytes);
                DataBufferUtils.release(buffer);
                for (byte[] line : lines.feed(bytes)) emitter.send(line, MediaType.APPLICATION_OCTET_STREAM);
            }
            byte[] rest = lines.drain();
            if (rest.length > 0) emitter.send(rest, MediaType.APPLICATION_OCTET_STREAM);
        } catch (Exception ex) {
            // The caller hung up, the upstream went quiet past its allowance, or it closed on us. All
            // three end the stream the same way, and none of them is anybody's fault worth a stack.
            if (!closed.get()) log.debug("Stream ended: {}", ex.toString());
        } finally {
            close();
        }
    }

    /** Ends the stream from anywhere: the caller, the upstream, a timeout, or a revocation. */
    void close() {
        if (!closed.compareAndSet(false, true)) return;
        var held = upstream;
        if (held != null) held.close();
        try {
            emitter.complete();
        } catch (RuntimeException ex) {
            // Already completed by the container; nothing left to end.
        }
        onEnd.run();
    }

    /**
     * Cuts a byte stream into complete lines and scrubs each one. Lines end at {@code \n}, which in
     * UTF-8 can never fall inside a multi-byte character, so a line is always whole text.
     */
    static final class Lines {
        private final String[] secrets;
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

        Lines(String[] secrets) {
            this.secrets = secrets;
        }

        List<byte[]> feed(byte[] chunk) {
            var out = new ArrayList<byte[]>();
            int start = 0;
            for (int i = 0; i < chunk.length; i++) {
                if (chunk[i] != '\n') continue;
                pending.write(chunk, start, i - start + 1);
                out.add(scrubbed());
                start = i + 1;
            }
            pending.write(chunk, start, chunk.length - start);
            if (pending.size() > MAX_LINE_BYTES) out.add(scrubbed());
            return out;
        }

        byte[] drain() {
            return pending.size() == 0 ? new byte[0] : scrubbed();
        }

        private byte[] scrubbed() {
            String line = pending.toString(StandardCharsets.UTF_8);
            pending.reset();
            return SecretRedactor.scrubText(line, secrets).getBytes(StandardCharsets.UTF_8);
        }
    }
}
