package io.janus.gateway;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.janus.gateway.graphql.GraphQlProperties;
import io.janus.shared.ErrorCode;

/** What holds a subscription open, and what closes it. */
class GraphQlStreamingTest {

    // --- the register -----------------------------------------------------------

    private final GraphQlStreams streams = new GraphQlStreams(new GraphQlProperties(300, 0, 2, 100, 100, 25));

    private GraphQlStreams.Admission admission(UUID application, UUID grant) {
        return new GraphQlStreams.Admission(application, grant, UUID.randomUUID(), UUID.randomUUID());
    }

    @Test
    void refusesAnApplicationItsThirdStreamWithItsOwnCode() {
        var application = UUID.randomUUID();
        streams.open(admission(application, UUID.randomUUID()), () -> {});
        streams.open(admission(application, UUID.randomUUID()), () -> {});

        var refused = catchThrowableOfType(
                Throttled.class, () -> streams.open(admission(application, UUID.randomUUID()), () -> {}));

        assertThat(refused.code).isEqualTo(ErrorCode.STREAM_LIMIT);
        // Somebody else's share is theirs.
        streams.open(admission(UUID.randomUUID(), UUID.randomUUID()), () -> {});
    }

    @Test
    void aClosedStreamGivesItsPlaceBack() {
        var application = UUID.randomUUID();
        var first = streams.open(admission(application, UUID.randomUUID()), () -> {});
        streams.open(admission(application, UUID.randomUUID()), () -> {});

        first.close();
        first.close();

        streams.open(admission(application, UUID.randomUUID()), () -> {});
        assertThat(streams.active()).isEqualTo(2);
    }

    /** A grant withdrawn must not go on being honoured by the streams it already opened. */
    @Test
    void closesTheStreamsARevocationMadeUnauthorised() {
        var grant = UUID.randomUUID();
        var closedByRevocation = new AtomicInteger();
        var untouched = new AtomicInteger();
        streams.open(admission(UUID.randomUUID(), grant), closedByRevocation::incrementAndGet);
        streams.open(admission(UUID.randomUUID(), UUID.randomUUID()), untouched::incrementAndGet);

        assertThat(streams.closeGrant(grant)).isEqualTo(1);
        assertThat(closedByRevocation).hasValue(1);
        assertThat(untouched).hasValue(0);
    }

    // --- the relay --------------------------------------------------------------

    private static final String SECRET = "sk_live_31337";

    private static String text(java.util.List<byte[]> lines) {
        var joined = new StringBuilder();
        for (byte[] line : lines) joined.append(new String(line, StandardCharsets.UTF_8));
        return joined.toString();
    }

    @Test
    void relaysCompleteLinesAndHoldsBackTheRest() {
        var lines = new StreamRelay.Lines(new String[] {SECRET});

        var out = lines.feed("data: {\"a\":1}\n\ndata: {\"b\"".getBytes(StandardCharsets.UTF_8));

        assertThat(text(out)).isEqualTo("data: {\"a\":1}\n\n");
        assertThat(new String(lines.drain(), StandardCharsets.UTF_8)).isEqualTo("data: {\"b\"");
    }

    /** Scrubbed per line, so a secret arriving in two network reads cannot slip through in halves. */
    @Test
    void scrubsASecretSplitAcrossTwoReads() {
        var lines = new StreamRelay.Lines(new String[] {SECRET});

        var first = lines.feed("data: token=sk_live_".getBytes(StandardCharsets.UTF_8));
        var second = lines.feed("31337\n".getBytes(StandardCharsets.UTF_8));

        assertThat(first).isEmpty();
        assertThat(text(second)).isEqualTo("data: token=" + SecretRedactor.PLACEHOLDER + "\n");
    }

    private static org.springframework.core.io.buffer.DataBuffer buffer(String text) {
        return org.springframework.core.io.buffer.DefaultDataBufferFactory.sharedInstance.wrap(
                text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void relaysAStreamToItsEndAndThenReleasesItsPlace() throws Exception {
        var relay = new StreamRelay(0);
        var ended = new java.util.concurrent.CountDownLatch(1);

        relay.start(
                reactor.core.publisher.Flux.just(buffer("data: {\"a\":1}\n\n"), buffer("tail")),
                new String[] {SECRET},
                ended::countDown);

        assertThat(ended.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    }

    /** A revocation that arrives while the upstream is still being called ends the stream on arrival. */
    @Test
    void aRelayClosedBeforeItStartsEndsAtOnce() {
        var relay = new StreamRelay(1000);
        var ended = new AtomicInteger();

        relay.close();
        relay.start(reactor.core.publisher.Flux.never(), new String[0], ended::incrementAndGet);

        assertThat(ended).hasValue(1);
    }

    @Test
    void closingARunningRelayEndsItOnce() throws Exception {
        var relay = new StreamRelay(0);
        var ended = new java.util.concurrent.CountDownLatch(1);
        relay.start(reactor.core.publisher.Flux.never(), new String[0], ended::countDown);

        relay.close();
        relay.close();

        assertThat(ended.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(relay.emitter()).isNotNull();
    }

    @Test
    void relaysALineLongerThanItsCeilingInPieces() {
        var lines = new StreamRelay.Lines(new String[0]);

        var out = lines.feed(new byte[StreamRelay.MAX_LINE_BYTES + 1]);

        assertThat(out).hasSize(1);
        assertThat(lines.drain()).isEmpty();
    }

    @Test
    void acceptsTheCloseCodesAPeerMaySendAndReplacesTheOthers() {
        assertThat(GraphQlSocketHandler.relayable(4403, "Forbidden").getCode()).isEqualTo(4403);
        assertThat(GraphQlSocketHandler.relayable(1000, null).getCode()).isEqualTo(1000);
        assertThat(GraphQlSocketHandler.relayable(1006, "abnormal").getCode()).isEqualTo(1011);
    }
}
