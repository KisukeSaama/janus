package io.janus.credentials;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import io.janus.IntegrationTest;

/**
 * Single use of an authorisation state, against a database that can roll a transaction back.
 *
 * <p>The callback deletes the state before it does anything else, with a comment saying "single use,
 * whatever happens next" — and then reports every way the exchange can fail by throwing, which used
 * to roll the deletion back along with everything else. The state stayed valid for the rest of its
 * fifteen minutes on an endpoint that is necessarily unauthenticated: a browser returning from
 * another site carries no session cookie, so the state is the only thing this is judged on.
 *
 * <p>A mocked repository cannot see the difference, because there is no transaction to undo. Hence a
 * real database, and a test that is deliberately not transactional itself.
 */
class AuthorizationStateIT extends IntegrationTest {

    private static final UUID BOOTSTRAP = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired
    private CredentialAuthorizationService authorizations;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    /**
     * The expiry branch stands for all four: it is the first refusal after the state is consumed, so
     * a state that survives it survives every one of them.
     */
    @Test
    void aStateIsUsedUpEvenWhenTheExchangeItStartedIsRefused() {
        var provider = UUID.randomUUID();
        var credential = UUID.randomUUID();
        String state = "probe-" + UUID.randomUUID();
        givenAnExpiredAuthorization(provider, credential, state);

        assertThatThrownBy(() -> authorizations.complete(state, "the-code"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(stateCount(state))
                .as("a state whose exchange failed must not be usable a second time")
                .isZero();

        // And the second presentation is refused on its own, rather than being told it expired —
        // which is what it would say if the row were somehow still there.
        assertThatThrownBy(() -> authorizations.complete(state, "the-code"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no longer valid");
        jdbc().update("delete from providers where id = ?", provider);
    }

    private void givenAnExpiredAuthorization(UUID provider, UUID credential, String state) {
        jdbc().update(
                        "insert into providers (id, name, slug, base_url, auth_type) values (?, ?, ?, ?, 'NONE')",
                        provider,
                        "states-" + provider,
                        "states-" + provider,
                        "https://example.test");
        jdbc().update(
                        """
                insert into credentials (id, name, provider_id, auth_type, secret_path, owner_id)
                values (?, ?, ?, 'NONE', ?, ?)
                """,
                        credential,
                        "states-" + credential,
                        provider,
                        "janus/" + credential + "/credential",
                        BOOTSTRAP);
        jdbc().update(
                        """
                insert into oauth_authorization_states
                  (state, credential_id, account_id, code_verifier, redirect_uri, expires_at)
                values (?, ?, ?, ?, ?, ?)
                """,
                        state,
                        credential,
                        BOOTSTRAP,
                        "verifier-that-nothing-will-read",
                        "https://janus.example.test/oauth/callback",
                        java.sql.Timestamp.from(Instant.now().minusSeconds(60)));
    }

    private int stateCount(String state) {
        return jdbc().queryForObject(
                        "select count(*) from oauth_authorization_states where state = ?", Integer.class, state);
    }
}
