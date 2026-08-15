package io.janus.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import io.janus.IntegrationTest;

/**
 * Rotation and reuse detection, against a database that can actually roll a transaction back.
 *
 * <p>Both properties asserted here were green in the unit tests while being false in production, and
 * for the same reason: a mocked repository records that {@code deleteByFamilyId} was called, and a
 * real one records that it was called and then undone. The revocation is reported to the caller by
 * throwing, the throw leaves an {@code @Transactional} method, and Spring rolls back everything that
 * method wrote — including the revocation. Nothing short of a real transaction can tell the two
 * apart, which is why this class exists rather than another mock.
 *
 * <p>Deliberately not transactional itself: what is being asked is whether the service's own
 * transaction committed, and a test transaction wrapping it would answer a different question.
 */
class RefreshRotationIT extends IntegrationTest {

    private static final UUID BOOTSTRAP = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired
    private OAuthTokenService tokens;

    @Autowired
    private RefreshTokenRepository refreshTokens;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    /**
     * The property the whole scheme exists for. A thief who uses a stolen token first gets a working
     * pair; the legitimate client then presents the value it still holds, Janus recognises the reuse
     * and drops the family. If that drop does not survive the refusal that announces it, the thief
     * keeps the access and the journal says otherwise — which is worse than not detecting it at all.
     */
    @Test
    void aReplayedRefreshTokenLosesItsWholeFamilyForGood() {
        var application = UUID.randomUUID();
        String key = anApplication(application);

        var first = tokens.clientCredentials(application.toString(), key);
        var second = tokens.refresh(first.refreshToken());
        assertThat(held(second.refreshToken()))
                .as("the successor the legitimate rotation issued")
                .isTrue();

        assertThatThrownBy(() -> tokens.refresh(first.refreshToken())).isInstanceOf(OAuthException.class);

        assertThat(held(second.refreshToken()))
                .as("no token of the family survives a detected replay")
                .isFalse();
        forget(application);
    }

    /** Same question, for the other two writes the same method reports by throwing. */
    @Test
    void anExpiredTokenAndADisabledServiceBothLoseTheirRowsForGood() {
        var application = UUID.randomUUID();
        String key = anApplication(application);
        var issued = tokens.clientCredentials(application.toString(), key);

        jdbc().update(
                        "update application_refresh_tokens set expires_at = now() - interval '1 hour' where "
                                + "application_id = ?",
                        application);
        assertThatThrownBy(() -> tokens.refresh(issued.refreshToken())).isInstanceOf(OAuthException.class);
        assertThat(held(issued.refreshToken())).isFalse();

        var again = tokens.clientCredentials(application.toString(), key);
        jdbc().update("update applications set enabled = false where id = ?", application);

        assertThatThrownBy(() -> tokens.refresh(again.refreshToken())).isInstanceOf(OAuthException.class);
        assertThat(held(again.refreshToken())).isFalse();
        forget(application);
    }

    /**
     * The race the {@code spent()} check cannot close on its own. Two requests carrying the same
     * value both read a row nobody has retired yet; without a conditional claim both pass, both are
     * issued a successor, and reuse detection is defeated by arriving twice at once rather than
     * twice in a row.
     *
     * <p>Not timing-dependent in its outcome. Whichever way the two interleave, exactly one may be
     * issued a pair, and the family goes either way — the loser is a replay, and a replay costs the
     * chain.
     */
    @Test
    void twoRequestsPresentingTheSameTokenAtOnceDoNotBothGetASuccessor() throws Exception {
        var application = UUID.randomUUID();
        String key = anApplication(application);
        var issued = tokens.clientCredentials(application.toString(), key);

        var together = new CyclicBarrier(2);
        var attempts = List.of(
                CompletableFuture.supplyAsync(() -> attempt(together, issued.refreshToken())),
                CompletableFuture.supplyAsync(() -> attempt(together, issued.refreshToken())));
        CompletableFuture.allOf(attempts.toArray(CompletableFuture[]::new)).join();

        long succeeded = attempts.stream()
                .map(CompletableFuture::join)
                .filter(java.util.Objects::nonNull)
                .count();
        assertThat(succeeded)
                .as("exactly one of two simultaneous rotations is honoured")
                .isEqualTo(1);
        assertThat(jdbc().queryForObject(
                                "select count(*) from application_refresh_tokens where application_id = ?",
                                Integer.class,
                                application))
                .as("the family goes with the replay, whichever request was the one that lost")
                .isZero();
        forget(application);
    }

    /** Null when the exchange was refused, which is what the count above is reading. */
    private TokenResponse attempt(CyclicBarrier together, String presented) {
        try {
            together.await();
            return tokens.refresh(presented);
        } catch (OAuthException refused) {
            return null;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        } catch (java.util.concurrent.BrokenBarrierException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** An application holding a key nobody else knows, and the key. */
    private String anApplication(UUID id) {
        String key = "jns_rotation_probe_" + id;
        jdbc().update(
                        "insert into applications (id, name, api_key_hash, owner_id) values (?, ?, ?, ?)",
                        id,
                        "rotation-" + id,
                        encoder.encode(key),
                        BOOTSTRAP);
        return key;
    }

    private boolean held(String refreshToken) {
        return refreshTokens
                .findByTokenHash(AccessTokenStore.digest(refreshToken))
                .isPresent();
    }

    private void forget(UUID application) {
        jdbc().update("delete from applications where id = ?", application);
    }
}
