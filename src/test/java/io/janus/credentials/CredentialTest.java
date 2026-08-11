package io.janus.credentials;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import io.janus.accounts.TestAccount;
import io.janus.providers.Provider;

class CredentialTest {
    private static final Instant DEADLINE = Instant.parse("2026-09-01T00:00:00Z");

    private static Credential withDeadlineAnnouncedAt(ExpiryStage stage) {
        var credential = credentialExpiring(DEADLINE);
        credential.claimExpiryStage(stage);
        return credential;
    }

    private static Credential credentialExpiring(Instant expiresAt) {
        var provider = new Provider(
                TestAccount.owner(),
                "Payments",
                "payments",
                "https://api.example.com",
                true,
                new Provider.TrafficPolicy(true, 0, 0, 0));
        return new Credential(provider, "key", Credential.Strategy.of(AuthType.BEARER), expiresAt, true);
    }

    private static void moveDeadline(Credential credential, Instant expiresAt) {
        credential.describe(credential.getName(), Credential.Strategy.of(credential.getAuthType()), expiresAt, true);
    }

    @Test
    void movingTheDeadlineRearmsTheAnnouncements() {
        var credential = withDeadlineAnnouncedAt(ExpiryStage.WARNING);
        moveDeadline(credential, DEADLINE.plusSeconds(86_400 * 90));
        assertThat(credential.getExpiryStageNotified()).isNull();
    }

    /** A save that rewrites the same date is not a new promise, and must not start the noise again. */
    @Test
    void rewritingTheSameDeadlineKeepsWhatHasAlreadyBeenSaid() {
        var credential = withDeadlineAnnouncedAt(ExpiryStage.WARNING);
        moveDeadline(credential, DEADLINE);
        assertThat(credential.getExpiryStageNotified()).isEqualTo(ExpiryStage.WARNING);
    }

    @Test
    void clearingTheDeadlineRearmsTheAnnouncements() {
        var credential = withDeadlineAnnouncedAt(ExpiryStage.EXPIRED);
        moveDeadline(credential, null);
        assertThat(credential.getExpiryStageNotified()).isNull();
        assertThat(credential.getExpiresAt()).isNull();
    }

    /**
     * Nothing is stored for an open API, so nothing about it can stop working on a date. A deadline
     * carried over from the strategy it used to have would have the register announce the expiry of a
     * secret that no longer exists.
     */
    @Test
    void becomingAnOpenApiDropsTheDeadline() {
        var credential = withDeadlineAnnouncedAt(ExpiryStage.NOTICE);
        credential.describe(credential.getName(), Credential.Strategy.of(AuthType.NONE), DEADLINE, true);
        assertThat(credential.getExpiresAt()).isNull();
        assertThat(credential.getExpiryStageNotified()).isNull();
    }

    /** The path a secret lives at is derived once and never moves when the record is edited. */
    @Test
    void theSecretPathIsDerivedFromTheProviderAndNeverMoves() {
        var credential = credentialExpiring(DEADLINE);
        String path = credential.getSecretPath();
        assertThat(path).startsWith("janus/payments/");
        moveDeadline(credential, DEADLINE.plusSeconds(86_400));
        assertThat(credential.getSecretPath()).isEqualTo(path);
    }
}
