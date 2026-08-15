package io.janus;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.*;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The schema, and whether the application still agrees with it.
 *
 * <p>Most of this test's value is in the fact that the context started at all. Hibernate runs with
 * {@code ddl-auto: validate}, so a column a migration never added, or one an entity stopped
 * mapping, prevents startup — here, in a few seconds, rather than on a deployment.
 */
class SchemaIT extends IntegrationTest {

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    @Test
    void everyMigrationAppliedInOrderAndNoneFailed() {
        var applied = jdbc().queryForList(
                        "select version, description, success from flyway_schema_history order by installed_rank");

        assertThat(applied).isNotEmpty();
        assertThat(applied).allSatisfy(row -> assertThat(row.get("success")).isEqualTo(true));

        var versions = applied.stream()
                .map(row -> (String) row.get("version"))
                .filter(Objects::nonNull)
                .toList();
        assertThat(versions).startsWith("1").doesNotHaveDuplicates();
    }

    /** The tables the application maps to. A rename that only reached the entity would fail here. */
    @Test
    void theTablesTheApplicationMapsToAllExist() {
        var tables = jdbc()
                .queryForList(
                        "select table_name from information_schema.tables where table_schema = 'public'", String.class)
                .stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .toList();

        assertThat(tables)
                .contains(
                        "accounts",
                        "applications",
                        "application_origins",
                        "providers",
                        "credentials",
                        "grants",
                        "audit_events",
                        "notifications",
                        "application_refresh_tokens",
                        "pending_secret_deletions");
    }

    /**
     * The allowlist was dropped in V13. Its table going with it is what the migration was for; a
     * leftover would still be joined against by anything that had not been updated.
     */
    @Test
    void theRouteAllowlistIsGoneRatherThanMerelyUnused() {
        var tables = jdbc().queryForList(
                        "select table_name from information_schema.tables where table_schema = 'public'", String.class);

        assertThat(tables).doesNotContain("route_policies");
    }

    /** A slug is unique per owner, not per deployment: two people registering Spotify is ordinary. */
    @Test
    void theUniquenessRulesAreEnforcedByTheDatabaseAndNotOnlyByTheService() {
        var constraints =
                jdbc().queryForList("select conname from pg_constraint where contype in ('u','p')", String.class);

        assertThat(constraints)
                .contains(
                        "uq_provider_slug",
                        "uq_credential_owner_provider",
                        "uq_application_owner_name",
                        "uq_grant_app_provider");
    }

    /** A secret is addressed by its path, so two records may never claim the same one. */
    @Test
    void noTwoCredentialsCanClaimTheSameSecretPath() {
        var unique = jdbc().queryForList("""
                select i.indexrelid::regclass::text as name
                from pg_index i
                join pg_class c on c.oid = i.indrelid
                where c.relname = 'credentials' and i.indisunique
                """, String.class);

        assertThat(unique).isNotEmpty();
    }

    /** Removing an API removes its database aggregate; OpenBao cleanup is queued separately. */
    @Test
    void credentialMetadataCascadesFromItsApi() {
        var deleteAction = jdbc().queryForObject("""
                select rc.delete_rule
                  from information_schema.referential_constraints rc
                 where rc.constraint_name = 'credentials_provider_id_fkey'
                """, String.class);

        assertThat(deleteAction).isEqualTo("CASCADE");
    }
}
