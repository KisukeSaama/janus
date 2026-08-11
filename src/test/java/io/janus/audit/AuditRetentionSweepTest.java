package io.janus.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * What the trim is allowed to remove, and how it goes about it.
 *
 * <p>Two things matter here and neither is visible from the SQL alone: that the two halves of the
 * stream age at their own rates, so a month of traffic never takes a year of administrative history
 * with it; and that a backlog is removed a batch at a time rather than in one statement, which is
 * what lets this run against a table the gateway is still writing to.
 */
class AuditRetentionSweepTest {
    private static final Instant NOW = Instant.parse("2026-08-11T03:45:00Z");

    private final AuditEventRepository repository = Mockito.mock(AuditEventRepository.class);

    private AuditRetentionSweep sweep(AuditRetentionProperties properties) {
        return new AuditRetentionSweep(repository, properties);
    }

    private static AuditRetentionProperties keeping(int trafficDays, int administrativeDays, int batchSize) {
        return new AuditRetentionProperties(trafficDays, administrativeDays, "0 45 3 * * *", "UTC", batchSize);
    }

    // --- the two ages --------------------------------------------------------

    @Test
    void agesTrafficAndEverythingElseSeparately() {
        sweep(keeping(30, 365, 5000)).trim(NOW);

        var trafficCutoff = ArgumentCaptor.forClass(Instant.class);
        verify(repository).deleteBeforeMatching(trafficCutoff.capture(), anyCollection(), eq(5000));
        assertThat(trafficCutoff.getValue()).isEqualTo(NOW.minus(Duration.ofDays(30)));

        var restCutoff = ArgumentCaptor.forClass(Instant.class);
        verify(repository).deleteBeforeExcept(restCutoff.capture(), anyCollection(), eq(5000));
        assertThat(restCutoff.getValue()).isEqualTo(NOW.minus(Duration.ofDays(365)));
    }

    /**
     * Traffic is what is written once per attempt at the door: a call, and the three refusals. The
     * refusals are here on purpose — they are written at whatever rate somebody chooses to knock, so
     * leaving them in the year-long half would let a scripted flood decide how much disk Janus keeps.
     */
    @Test
    void countsEveryPerRequestActionAsTraffic() {
        sweep(keeping(30, 365, 5000)).trim(NOW);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> actions = ArgumentCaptor.forClass(Collection.class);
        verify(repository).deleteBeforeMatching(any(), actions.capture(), anyInt());

        assertThat(actions.getValue())
                .containsExactlyInAnyOrder(
                        AuditAction.GATEWAY_REQUEST.name(),
                        AuditAction.GATEWAY_AUTHENTICATION.name(),
                        AuditAction.ADMIN_AUTHENTICATION.name(),
                        AuditAction.OAUTH_TOKEN_ISSUED.name());
    }

    /** What was actually granted is administrative, however often the attempt beside it was refused. */
    @Test
    void keepsWhatSucceededOutOfTheTrafficHalf() {
        sweep(keeping(30, 365, 5000)).trim(NOW);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> actions = ArgumentCaptor.forClass(Collection.class);
        verify(repository).deleteBeforeMatching(any(), actions.capture(), anyInt());

        assertThat(actions.getValue())
                .doesNotContain(
                        AuditAction.ACCOUNT_SIGNED_IN.name(),
                        AuditAction.OAUTH_TOKEN_REPLAYED.name(),
                        AuditAction.GATEWAY_CACHE_PURGED.name(),
                        AuditAction.CREDENTIAL_CREATED.name());
    }

    /** The same list both ways round, or a rename would leave rows in neither half. */
    @Test
    void appliesTheSameSplitToBothHalves() {
        sweep(keeping(30, 365, 5000)).trim(NOW);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> traffic = ArgumentCaptor.forClass(Collection.class);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> rest = ArgumentCaptor.forClass(Collection.class);

        verify(repository).deleteBeforeMatching(any(), traffic.capture(), anyInt());
        verify(repository).deleteBeforeExcept(any(), rest.capture(), anyInt());

        assertThat(rest.getValue()).containsExactlyElementsOf(traffic.getValue());
    }

    // --- keeping forever -----------------------------------------------------

    @Test
    void removesNothingWhenAnAgeIsZero() {
        sweep(keeping(0, 0, 5000)).trim(NOW);

        verifyNoInteractions(repository);
    }

    /** One half kept forever must not stop the other from being trimmed. */
    @Test
    void trimsTrafficEvenWhenTheRestIsKeptForever() {
        sweep(keeping(30, 0, 5000)).trim(NOW);

        verify(repository).deleteBeforeMatching(any(), anyCollection(), anyInt());
        verify(repository, never()).deleteBeforeExcept(any(), anyCollection(), anyInt());
    }

    // --- the batching --------------------------------------------------------

    @Test
    void keepsGoingWhileEveryBatchComesBackFull() {
        when(repository.deleteBeforeMatching(any(), anyCollection(), eq(100))).thenReturn(100, 100, 40);

        int removed = sweep(keeping(30, 0, 100)).trim(NOW);

        assertThat(removed).isEqualTo(240);
        verify(repository, times(3)).deleteBeforeMatching(any(), anyCollection(), eq(100));
    }

    /** A short first batch is the whole answer: nothing older than the cutoff is left to find. */
    @Test
    void stopsAtTheFirstBatchThatComesBackShort() {
        when(repository.deleteBeforeMatching(any(), anyCollection(), eq(100))).thenReturn(7);

        int removed = sweep(keeping(30, 0, 100)).trim(NOW);

        assertThat(removed).isEqualTo(7);
        verify(repository).deleteBeforeMatching(any(), anyCollection(), eq(100));
    }

    /** An empty table costs one statement, not a thousand. */
    @Test
    void stopsImmediatelyWhenThereIsNothingToRemove() {
        when(repository.deleteBeforeMatching(any(), anyCollection(), anyInt())).thenReturn(0);
        when(repository.deleteBeforeExcept(any(), anyCollection(), anyInt())).thenReturn(0);

        assertThat(sweep(keeping(30, 365, 5000)).trim(NOW)).isZero();

        verify(repository).deleteBeforeMatching(any(), anyCollection(), anyInt());
        verify(repository).deleteBeforeExcept(any(), anyCollection(), anyInt());
    }

    /**
     * A delete that keeps reporting a full batch — an installation being trimmed for the first time,
     * or a runaway — gives up rather than running until morning. What is left is the next run's.
     */
    @Test
    void givesUpRatherThanLoopingWhenTheBacklogNeverEnds() {
        when(repository.deleteBeforeMatching(any(), anyCollection(), eq(100))).thenReturn(100);

        int removed = sweep(keeping(30, 0, 100)).trim(NOW);

        assertThat(removed).isEqualTo(100_000);
        verify(repository, times(1_000)).deleteBeforeMatching(any(), anyCollection(), eq(100));
    }

    // --- the settings themselves ---------------------------------------------

    @Test
    void refusesAgesAndBatchSizesThatMakeNoSense() {
        assertThat(rejected(() -> keeping(-1, 365, 5000))).isTrue();
        assertThat(rejected(() -> keeping(30, -1, 5000))).isTrue();
        assertThat(rejected(() -> keeping(30, 365, 0))).isTrue();
    }

    private static boolean rejected(Runnable construction) {
        try {
            construction.run();
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }

    /**
     * The gateway actions, listed so that adding one is a decision about which half it ages in rather
     * than a silent inheritance of the year.
     */
    @Test
    void namesEveryActionTheGatewayCanWrite() {
        var gateway = List.of(AuditAction.values()).stream()
                .filter(action -> action.name().startsWith("GATEWAY_"))
                .map(Enum::name)
                .toList();

        assertThat(gateway)
                .containsExactlyInAnyOrder(
                        AuditAction.GATEWAY_REQUEST.name(),
                        AuditAction.GATEWAY_AUTHENTICATION.name(),
                        // Rare, and an administrator's doing despite the name: it ages with the rest.
                        AuditAction.GATEWAY_CACHE_PURGED.name());
    }
}
