package io.janus.providers;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import io.janus.IntegrationTest;

/**
 * Removing an API, with the records an API actually has by the time anybody wants it gone.
 *
 * <p>The unit tests answer this against mocked repositories, which is exactly the thing that cannot
 * fail here: what breaks is the flush at the end of the transaction, on entities the service loaded
 * to clean up after. Only a real session and a real database decide that.
 */
class ProviderDeletionIT extends IntegrationTest {

    private static final UUID BOOTSTRAP = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    @Test
    void removesAnApiThatAnApplicationIsConnectedTo() {
        var application = UUID.randomUUID();
        var provider = UUID.randomUUID();
        var credential = UUID.randomUUID();
        var grant = UUID.randomUUID();
        givenAnApiConnectedToAnApplication(application, provider, credential, grant);

        http().delete()
                .uri("/api/admin/providers/" + provider)
                .headers(headers -> headers.setBasicAuth(ADMIN_USERNAME, ADMIN_PASSWORD))
                .exchange()
                .expectStatus()
                .isOk();

        assertThat(count("grants", grant)).isZero();
        assertThat(count("credentials", credential)).isZero();
        assertThat(count("providers", provider)).isZero();
        jdbc().update("delete from applications where id = ?", application);
    }

    /** The plain case, which has no connection to trip over, so a regression here is unambiguous. */
    @Test
    void removesAnApiNobodyHasActivated() {
        var provider = UUID.randomUUID();
        insertProvider(provider, "lonely");

        http().delete()
                .uri("/api/admin/providers/" + provider)
                .headers(headers -> headers.setBasicAuth(ADMIN_USERNAME, ADMIN_PASSWORD))
                .exchange()
                .expectStatus()
                .isOk();

        assertThat(count("providers", provider)).isZero();
    }

    private void givenAnApiConnectedToAnApplication(UUID application, UUID provider, UUID credential, UUID grant) {
        jdbc().update(
                        "insert into applications (id, name, api_key_hash, owner_id) values (?, ?, ?, ?)",
                        application,
                        "connected-" + application,
                        "$2a$10$deletion.probe.hash",
                        BOOTSTRAP);
        insertProvider(provider, "connected");
        jdbc().update(
                        """
                insert into credentials (id, name, provider_id, auth_type, secret_path, owner_id)
                values (?, ?, ?, 'NONE', ?, ?)
                """,
                        credential,
                        "connected-" + credential,
                        provider,
                        "janus/" + credential + "/credential",
                        BOOTSTRAP);
        jdbc().update(
                        "insert into grants (id, application_id, provider_id, credential_id) values (?, ?, ?, ?)",
                        grant,
                        application,
                        provider,
                        credential);
    }

    private void insertProvider(UUID id, String label) {
        jdbc().update(
                        "insert into providers (id, name, slug, base_url, auth_type) values (?, ?, ?, ?, 'NONE')",
                        id,
                        label + "-" + id,
                        label + "-" + id,
                        "https://example.test");
    }

    private int count(String table, UUID id) {
        return jdbc().queryForObject("select count(*) from " + table + " where id = ?", Integer.class, id);
    }
}
